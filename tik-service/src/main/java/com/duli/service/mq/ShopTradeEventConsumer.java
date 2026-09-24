package com.duli.service.mq;

import com.duli.service.IShopTradeService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 事务成功返回后由容器 ACK；异常触发三次有限重试，之后拒绝并进入死信队列。 */
@Component
public class ShopTradeEventConsumer {
    private final IShopTradeService trade;

    /** 注入统一交易状态机，避免消费端自行重复实现支付和库存逻辑。 */
    public ShopTradeEventConsumer(IShopTradeService trade,com.duli.service.ShopOrderSubmissionService submissions) {
        this.trade=trade;this.submissions=submissions;
    }
    private final com.duli.service.ShopOrderSubmissionService submissions;

    /** 下单使用独立队列与两个消费线程，隔离订单库存热点与通知处理。 */
    @RabbitListener(queues="shop.order.queue",containerFactory="shopOrderListenerFactory")
    // 受理事件进入独立队列；消费者按事件 ID 回查数据库并在受控线程内执行库存事务。
    public void order(String eventId) { submissions.consume(eventId); }

    /** MQ 只传事件 ID，消费端从数据库读取权威事件并幂等处理。 */
    @RabbitListener(queues=ShopTradeEventSender.QUEUE,containerFactory="shopTradeListenerFactory")
    public void consume(String eventId) { trade.consumeEvent(eventId); }
}
