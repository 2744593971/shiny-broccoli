package com.duli.task;
import com.duli.service.IShopTradeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** 定时关闭本地未支付订单。多实例也由订单行锁/状态检查保证库存只回补一次。 */
@Component
public class ShopOrderExpiryTask {
    private static final Logger log=LoggerFactory.getLogger(ShopOrderExpiryTask.class);
    private final IShopTradeService trade;
    /** 注入统一交易服务，复用同一关单状态机。 */
    public ShopOrderExpiryTask(IShopTradeService trade) { this.trade=trade; }
    /** 每分钟执行数据库扫描兜底，独立于 RabbitMQ 到期事件。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    // 即使延迟 MQ 投递失败，仍扫描数据库到期 WAIT_PAY 订单；状态与行锁保证只回补一次。
    public void closeExpired() {
        try { trade.expireOrders(); }
        catch(Exception error) {
            // 不输出订单地址、电话和密钥；失败下轮重试，事务已回滚。
            log.warn("商城超时关单批次未完成，请检查数据库/迁移状态，异常类型：{}",error.getClass().getSimpleName());
        }
    }
}
