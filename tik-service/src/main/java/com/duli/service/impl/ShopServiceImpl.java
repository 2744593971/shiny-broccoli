package com.duli.service.impl;

import com.duli.service.IShopService;
import com.duli.mapper.ShopRepository;
import com.duli.bo.ShopOrderBO;
import com.duli.pojo.ShopOrder;
import com.duli.vo.ShopItem;
import com.duli.exceptions.ShopException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/**
 * 同步事务版秒杀：条件扣库存 + 唯一索引 + 幂等返回。
 * 数据库是库存/订单唯一事实来源，不维护第二份 Redis 库存，避免跨系统回滚不一致。
 * 适合当前单体项目；不是宣称能承受任意流量的分布式秒杀平台。
 */
@Service
public class ShopServiceImpl implements IShopService {
    private final ShopRepository repository;
    private final TransactionTemplate transaction;
    private final com.duli.mapper.ShopTradeEventRepository events;

    /** 注入共享事务管理器和事件访问层，订单与到期事件一起提交。 */
    public ShopServiceImpl(ShopRepository repository, PlatformTransactionManager transactionManager, com.duli.mapper.ShopTradeEventRepository events) {
        this.repository = repository;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(5); // 竞争严重时及时失败；客户端用原 requestId 重试。
    }

    /** 按分页返回普通商品或秒杀场次。 */
    public Map<String, Object> products(int page, int size, boolean seckill) {
        validatePage(page, size);
        List<ShopItem> rows = seckill ? repository.activities((page-1)*size, size+1)
                : repository.products((page-1)*size, size+1);
        return page(rows, page, size);
    }
    /** 返回商品详情及服务端时钟。 */
    public Map<String, Object> detail(String productId, String activityId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", load(productId, activityId));
        result.put("serverTime", repository.now());
        return result;
    }
    /** 按当前用户分页读取订单。 */
    public Map<String, Object> orders(String userId, int page, int size) {
        validatePage(page, size);
        return page(repository.orders(userId, (page-1)*size, size+1), page, size);
    }
    /** 读取本人订单，拒绝不存在或越权的订单查询。 */
    public ShopOrder order(String userId, String id) {
        ShopOrder order = repository.order(id, userId);
        if (order == null) throw new ShopException(404, "订单不存在或无权查看");
        return order;
    }

    /** 幂等下单并占库存，在同一事务保存十五分钟后到期的关单事件。 */
    public ShopOrder place(String userId, ShopOrderBO input) {
        if (userId == null || userId.trim().isEmpty()) throw new ShopException(401, "请先登录");
        // 先查成功订单：网络超时后，即使活动已结束，也应该返回上次成功结果。
        ShopOrder existing = existing(userId, input);
        if (existing != null) return existing;
        try {
            return transaction.execute(status -> {//真正属于一个事务的是 place()方法里这一段
                ShopItem item = load(input.getProductId(), input.getActivityId());
                if (item.getActivityId() != null) {
                    long now = repository.now();
                    if (now < item.getStartsAt()) throw new ShopException(409, "秒杀尚未开始");
                    if (now >= item.getEndsAt()) throw new ShopException(409, "秒杀已经结束");
                    // 先锁活动，再锁商品。所有秒杀请求保持一致顺序，减少死锁机会。
                    if (repository.takeActivity(item.getActivityId()) != 1)
                        throw new ShopException(409, "本场名额已抢完或活动已结束");
                }
                if (repository.takeProduct(item.getId()) != 1)
                    throw new ShopException(409, "商品库存不足或已下架");
                ShopOrder order = new ShopOrder();
                order.setId(UUID.randomUUID().toString().replace("-", ""));
                order.setUserId(userId); // 只能来自 JWT request attribute，不能来自请求体。
                order.setProductId(item.getId());
                order.setActivityId(item.getActivityId());
                order.setRequestId(input.getRequestId());
                order.setTitle(item.getTitle());
                order.setCover(item.getCover());
                order.setAmount(item.getActivityId() == null ? item.getPrice() : item.getSeckillPrice());
                // 新单先保留库存，待支付；旧 DEMO_CONFIRMED 历史单不迁移为已支付。
                order.setStatus("WAIT_PAY");
                order.setReceiverName(input.getReceiverName());
                order.setReceiverPhone(input.getReceiverPhone());
                order.setReceiverAddress(input.getReceiverAddress());
                order.setExpiresAt(repository.now() + 15 * 60 * 1000L);
                repository.insert(order);
                events.enqueue("ORDER_EXPIRE",order.getId(),order.getExpiresAt());
                events.enqueue("ORDER_CREATED",order.getId(),repository.now());
                return repository.order(order.getId(), userId);
            });
        } catch (DuplicateKeyException | ShopException exception) {
            /*
             * 必须在 TransactionTemplate 外面捕获：此时事务已回滚，库存也已恢复。
             * 两个并发请求都通过前置检查时，由数据库唯一索引阻止第二张订单。
             * 最后一件被同一用户的另一请求抢到，也返回那张订单而不是错误地显示售罄。
             */
            existing = existing(userId, input);
            if (existing != null) return existing;
            throw exception;
        }
    }

    /** 响应丢失后按幂等键恢复订单，不要求客户端在本地持久化收货电话和地址。 */
    public ShopOrder orderByRequest(String userId,String requestId) {
        ShopOrder order=repository.byRequest(userId,requestId);
        if(order==null) throw new ShopException(404,"尚未查询到此请求的订单");
        return order;
    }

    /** 按请求号与场次查找历史订单，禁止重复下单绕过限购。 */
    private ShopOrder existing(String userId, ShopOrderBO input) {
        ShopOrder order = repository.byRequest(userId, input.getRequestId());
        if (order != null) {
            if (!Objects.equals(order.getProductId(), input.getProductId())
                    || !Objects.equals(order.getActivityId(), input.getActivityId()))
                throw new ShopException(409, "请求编号已用于其他商品，请重新发起购买");
            return order;
        }
        if (input.getActivityId() == null) return null;
        order = repository.byActivity(userId, input.getActivityId());
        if (order != null && !Objects.equals(order.getProductId(), input.getProductId()))
            throw new ShopException(409, "商品与活动不匹配");
        return order; // 一人一场一单；换 requestId 也不能突破限购。
    }
    /** 校验商品、场次与上下架状态。 */
    private ShopItem load(String productId, String activityId) {
        ShopItem item = activityId == null ? repository.product(productId) : repository.activity(activityId);
        if (item == null || !Objects.equals(item.getId(), productId))
            throw new ShopException(404, "商品/活动不存在、已下架或不匹配");
        return item;
    }
    /** 限制分页大小，避免无界查询。 */
    private void validatePage(int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 50)
            throw new ShopException(400, "分页参数超出范围");
    }
    /** 将查询结果整理为带服务器时间的统一分页响应。 */
    private <T> Map<String, Object> page(List<T> rows, int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasMore", rows.size() > size);
        result.put("rows", rows.subList(0, Math.min(rows.size(), size)));
        result.put("page", page);
        result.put("serverTime", repository.now());
        return result;
    }
}
