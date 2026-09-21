package com.duli.service.impl;
import com.duli.service.*;
import com.duli.mapper.*;
import com.duli.pojo.*;
import com.duli.bo.*;
import com.duli.exceptions.ShopException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/**
 * 本地测试交易状态机。任何状态变更都先锁订单，再在同一事务中更改支付记录/库存。
 * 外部真实支付不能简单照搬此事务：未来需可靠回调、主动查单、关单确认与退款补偿。
 */
@Service
public class ShopTradeServiceImpl implements IShopTradeService {
    private final ShopRepository shop;
    private final ShopTradeRepository trade;
    private final IPaymentGateway gateway;
    private final ShopTradeEventRepository events;
    private final TransactionTemplate tx;
    private final Set<String> admins=new HashSet<>();
    /** 注入订单、支付、Mock 渠道、事务和事件依赖，初始化管理员名单。 */
    public ShopTradeServiceImpl(ShopRepository shop,ShopTradeRepository trade,IPaymentGateway gateway,
            PlatformTransactionManager manager,@Value("${shop.admin-user-ids:}") String adminIds,ShopTradeEventRepository events) {
        this.shop=shop;this.trade=trade;this.gateway=gateway;this.events=events;this.tx=new TransactionTemplate(manager);
        this.tx.setTimeout(5);
        for(String id:adminIds.split(",")) if(!id.trim().isEmpty()) admins.add(id.trim());
    }
    /** 返回三个模拟渠道及当前用户的管理员权限。 */
    public Map<String,Object> config(String userId) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("testOnly",true);result.put("paymentEnabled",gateway.testEnabled());
        result.put("admin",admins.contains(userId));
        result.put("channels",Arrays.asList("WECHAT","ALIPAY","BANK_CARD"));
        result.put("notice","本地模拟支付，不是真实收款或官方沙箱；不填写银行卡资料");
        return result;
    }
    /** 校验登录身份并锁定本人订单，所有状态修改遵守同一锁顺序。 */
    private ShopOrder lock(String userId,String id) {
        if(userId==null||userId.trim().isEmpty()) throw new ShopException(401,"请先登录");
        ShopOrder order=trade.lock(id,userId);
        if(order==null) throw new ShopException(404,"订单不存在或无权操作");
        return order;
    }
    /** 校验服务端管理员名单，不能信任客户端传入的管理标志。 */
    private void admin(String userId) {
        if(!admins.contains(userId)) throw new ShopException(403,"没有商城管理权限");
    }
    /** 保护模拟支付入口，禁用环境不能生成模拟交易。 */
    private void testOnly() {
        if(!gateway.testEnabled()) throw new ShopException(503,"测试支付未开启；真实支付渠道尚未配置");
    }
    /** 防止内部调用绕过登录身份检查。 */
    private void requireUser(String userId) {
        if(userId==null||userId.trim().isEmpty()) throw new ShopException(401,"请先登录");
    }
    /** 集中校验订单状态机，不允许非法反向流转。 */
    private void state(ShopOrder order,String expected) {
        if(!expected.equals(order.getStatus())) throw new ShopException(409,"当前订单状态不允许此操作："+order.getStatus());
    }
    /** 读取同事务内更新后的订单快照作为响应。 */
    private ShopOrder current(ShopOrder order) { return shop.order(order.getId(),order.getUserId()); }

    /** 创建或复用一笔支付单，金额来自订单，Mock 待支付单允许切换渠道。 */
    public Map<String,Object> payment(String userId,ShopPaymentBO input) {
        requireUser(userId);
        testOnly();
        if(!Arrays.asList("WECHAT","ALIPAY","BANK_CARD").contains(input.getChannel()))
            throw new ShopException(400,"不支持的支付渠道");
        expireOne(input.getOrderId(),userId);
        return tx.execute(status->{
            ShopOrder order=lock(userId,input.getOrderId());
            state(order,"WAIT_PAY");
            if(order.getExpiresAt()==null||shop.now()>=order.getExpiresAt()) throw new ShopException(409,"订单已过期，请刷新");
            ShopPayment payment=trade.payment(order.getId());
            if(payment==null) {
                payment=new ShopPayment(); payment.setId(UUID.randomUUID().toString().replace("-",""));
                payment.setOrderId(order.getId());payment.setAmount(order.getAmount());
                payment.setChannel(input.getChannel());payment.setStatus("PENDING");
                trade.insertPayment(payment);
            } else {
                // 仅 mock 可复用同一支付单切换模拟渠道；真实渠道必须先确认旧交易已关闭。
                if(!"PENDING".equals(payment.getStatus())) throw new ShopException(409,"支付记录状态异常");
                trade.changeChannel(payment.getId(),input.getChannel());payment.setChannel(input.getChannel());
            }
            Map<String,Object> result=new LinkedHashMap<>(gateway.prepare(payment));
            result.put("payment",payment);result.put("order",order);result.put("serverTime",shop.now());
            return result;
        });
    }
    /** 兼容原测试确认入口：生成 Mock 通知后走统一回调，不直接置为 PAID。 */
    public ShopOrder confirmTest(String userId,String orderId) {
        requireUser(userId);testOnly();expireOne(orderId,userId);
        return tx.execute(status->{
            ShopOrder order=lock(userId,orderId);
            if(Arrays.asList("PAID","SHIPPED","COMPLETED").contains(order.getStatus())) return order;
            state(order,"WAIT_PAY");
            ShopPayment payment=trade.payment(orderId);
            if(payment==null||!"PENDING".equals(payment.getStatus())) throw new ShopException(409,"请先选择支付方式");
            ShopPaymentCallbackBO callback=gateway.notification(payment,gateway.confirmTest(payment));
            return applyCallback(userId,callback,gateway.verify(callback));
        });
    }

    /** 返回本人支付单、退款单与回调处理记录，供前端轮询和学习排查。 */
    public Map<String,Object> paymentDetail(String userId,String orderId) {
        requireUser(userId);
        return tx.execute(status->{
            ShopOrder order=lock(userId,orderId);
            ShopPayment payment=trade.payment(orderId);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("order",order);result.put("payment",payment);
            result.put("refund",trade.refundRecord(orderId));
            result.put("callbacks",payment==null?Collections.emptyList():trade.callbacks(payment.getId()));
            return result;
        });
    }

    /** 生成本人订单的签名模拟通知，便于手工重放；仅 Mock 环境可用。 */
    public ShopPaymentCallbackBO mockNotification(String userId,String orderId) {
        requireUser(userId);testOnly();
        return tx.execute(status->{
            lock(userId,orderId);
            ShopPayment payment=trade.payment(orderId);
            if(payment==null) throw new ShopException(409,"请先创建支付单");
            return gateway.notification(payment,gateway.confirmTest(payment));
        });
    }

    /** 验签后在订单锁内执行回调；唯一回调号冲突不能被当作成功吞掉。 */
    public ShopOrder callback(String userId,ShopPaymentCallbackBO callback) {
        requireUser(userId);
        String fingerprint=gateway.verify(callback);
        try {
            return tx.execute(status->applyCallback(userId,callback,fingerprint));
        } catch(org.springframework.dao.DuplicateKeyException error) {
            throw new ShopException(409,"回调事件号或渠道交易号已被其他支付使用");
        }
    }

    /** 核对权威支付单，幂等处理成功回调；迟到通知记录拒绝结果而不复活关闭订单。 */
    private ShopOrder applyCallback(String userId,ShopPaymentCallbackBO c,String fingerprint) {
        ShopOrder order=lock(userId,c.getOrderId());
        ShopPayment payment=trade.payment(order.getId());
        if(payment==null||!payment.getId().equals(c.getPaymentId())
                ||!payment.getChannel().equals(c.getChannel())
                ||payment.getAmount().compareTo(c.getAmount())!=0
                ||order.getAmount().compareTo(c.getAmount())!=0
                ||!("mock_"+payment.getId()).equals(c.getTradeId()))
            throw new ShopException(409,"回调支付单、金额、渠道或交易号不匹配");
        String previous=trade.callbackFingerprint(c.getEventId());
        if(previous!=null) {
            if(!previous.equals(fingerprint)) throw new ShopException(409,"同一回调事件号的内容不能改变");
            return order;
        }
        String outcome;
        if("WAIT_PAY".equals(order.getStatus())&&order.getExpiresAt()!=null&&shop.now()>=order.getExpiresAt()) {
            closeOrder(order,"EXPIRED");outcome="LATE_REJECTED";
        } else if(Arrays.asList("CANCELLED","EXPIRED").contains(order.getStatus())) {
            outcome="LATE_REJECTED";
        } else if(Arrays.asList("SUCCEEDED","REFUNDED").contains(payment.getStatus())) {
            if(!Objects.equals(payment.getExternalTradeId(),c.getTradeId()))
                throw new ShopException(409,"渠道交易号冲突");
            outcome="DUPLICATE";
        } else {
            state(order,"WAIT_PAY");
            if(!"PENDING".equals(payment.getStatus())||order.getExpiresAt()==null)
                throw new ShopException(409,"支付记录状态异常");
            trade.paid(order,payment,c.getTradeId());
            events.enqueue("PAYMENT_SUCCEEDED",order.getId(),shop.now());
            outcome="APPLIED";
        }
        trade.callback(c.getEventId(),payment.getId(),fingerprint,outcome);
        return current(order);
    }

    /** 幂等取消未支付订单，关闭支付并仅回补一次库存。 */
    public ShopOrder cancel(String userId,String id) {
        return tx.execute(status->{
            ShopOrder order=lock(userId,id);
            if(Arrays.asList("CANCELLED","EXPIRED").contains(order.getStatus())) return order;
            state(order,"WAIT_PAY");
            closeOrder(order,"CANCELLED");
            return current(order);
        });
    }
    /** 未发货订单全额 Mock 退款；退款单、订单、支付、库存和事件原子提交。 */
    public ShopOrder refund(String userId,String id) {
        testOnly();
        return tx.execute(status->{
            ShopOrder order=lock(userId,id);
            if("REFUNDED".equals(order.getStatus())) return order;
            // 已发货退款涉及退货/运费/审核，当前只支持未发货订单的本地模拟全额退款。
            state(order,"PAID");
            ShopPayment payment=trade.payment(id);
            if(payment==null||!"SUCCEEDED".equals(payment.getStatus())
                    ||payment.getExternalTradeId()==null||!payment.getExternalTradeId().startsWith("mock_"))
                throw new ShopException(409,"不是可退款的测试支付记录");
            if(payment.getAmount().compareTo(order.getAmount())!=0)
                throw new ShopException(409,"退款金额与订单不匹配");
            ShopRefund refund=trade.requestRefund(payment);
            String refundId=gateway.refundTest(payment);
            trade.refund(order,payment,refundId);
            trade.refundSucceeded(refund.getId(),refundId);
            events.enqueue("REFUND_SUCCEEDED",order.getId(),shop.now());
            return current(order);
        });
    }
    /** 买家幂等确认收货，只有已发货订单能够完成。 */
    public ShopOrder receive(String userId,String id) {
        return tx.execute(status->{
            ShopOrder order=lock(userId,id);
            if("COMPLETED".equals(order.getStatus())) return order;
            state(order,"SHIPPED");trade.receive(order);events.enqueue("ORDER_COMPLETED",order.getId(),shop.now());return current(order);
        });
    }
    /** 校验管理员与分页范围，查询可管理的订单。 */
    public Map<String,Object> adminOrders(String userId,int page,int size) {
        admin(userId);
        if(page<1||page>10000||size<1||size>50) throw new ShopException(400,"分页参数超出范围");
        List<ShopOrder> rows=trade.adminOrders((page-1)*size,size+1);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rows",rows.subList(0,Math.min(rows.size(),size)));
        result.put("hasMore",rows.size()>size);result.put("page",page);return result;
    }
    /** 管理员登记物流并发货；与退款竞争时由订单行锁裁决。 */
    public ShopOrder ship(String userId,ShopShipmentBO input) {
        admin(userId);
        return tx.execute(status->{
            ShopOrder order=trade.lock(input.getOrderId(),null);
            if(order==null) throw new ShopException(404,"订单不存在");
            if("SHIPPED".equals(order.getStatus())&&Objects.equals(order.getCarrier(),input.getCarrier())
                    &&Objects.equals(order.getTrackingNo(),input.getTrackingNo())) return order;
            state(order,"PAID");
            trade.ship(order,input.getCarrier().trim(),input.getTrackingNo().trim());
            events.enqueue("ORDER_SHIPPED",order.getId(),shop.now());
            return current(order);
        });
    }
    /** 以数据库时钟检查并关闭一笔超时未支付单。 */
    private void expireOne(String id,String owner) {
        tx.execute(status->{
            ShopOrder order=trade.lock(id,owner);
            if(order!=null&&"WAIT_PAY".equals(order.getStatus())&&order.getExpiresAt()!=null&&shop.now()>=order.getExpiresAt())
                closeOrder(order,"EXPIRED");
            return null;
        });
    }
    /** 扫描关单兜底，MQ 不可用时也能释放超时库存。 */
    public void expireOrders() {
        // 每批最多 100 单，每单独立事务，避免一次长事务锁住大量订单。
        for(String id:trade.expiredIds()) expireOne(id,null);
    }

    /** 先关闭 Mock 渠道，再在同一事务内关闭订单、释放库存并保存关闭事件。 */
    private void closeOrder(ShopOrder order,String status) {
        gateway.close(trade.payment(order.getId()));
        trade.close(order,status);
        events.enqueue("ORDER_CLOSED",order.getId(),shop.now());
    }

    /** 消费 MQ 事件：锁订单、核验事件、处理到期关单、写回执同事务提交。 */
    public void consumeEvent(String eventId) {
        tx.execute(status->{
            com.duli.pojo.ShopTradeEvent event=events.find(eventId);
            if(event==null) throw new ShopException(404,"交易事件不存在");
            ShopOrder order=trade.lock(event.getOrderId(),null);
            if(order==null) throw new ShopException(404,"事件对应订单不存在");
            if(events.consumed(eventId)) return null;
            switch(event.getEventType()) {
                case "ORDER_EXPIRE":
                    if("WAIT_PAY".equals(order.getStatus())) {
                        if(order.getExpiresAt()==null||shop.now()<order.getExpiresAt())
                            throw new ShopException(409,"订单尚未到期，不能提前关单");
                        closeOrder(order,"EXPIRED");
                    }
                    break;
                case "ORDER_CREATED":
                case "ORDER_SHIPPED":
                case "ORDER_COMPLETED":
                case "PAYMENT_SUCCEEDED":
                case "REFUND_SUCCEEDED":
                case "ORDER_CLOSED":
                    // 学习版下游以持久化交易回执为审计投影；支付结果由验签回调事务裁决。
                    break;
                default: throw new ShopException(400,"未知交易事件类型");
            }
            events.notifyOrder(event,order);
            events.receipt(event);return null;
        });
    }

    /** 仅管理员查询待处理失败事件，返回最多一百条。 */
    public List<com.duli.pojo.ShopTradeEvent> failedEvents(String userId) {
        admin(userId);return events.failures();
    }

    /** 仅管理员重放未消费的失败事件；已处理事件不会重复执行。 */
    public boolean replayEvent(String userId,String id) {
        admin(userId);return events.replay(id);
    }
}
