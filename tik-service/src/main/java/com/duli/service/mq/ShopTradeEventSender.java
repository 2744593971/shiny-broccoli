package com.duli.service.mq;

import com.duli.pojo.ShopTradeEvent;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** 交易事件发送器：只有 ACK 且无 Return 才视为成功。 */
@Component
public class ShopTradeEventSender {
    public static final String EXCHANGE="shop.trade.exchange";
    public static final String QUEUE="shop.trade.queue";
    public static final String ROUTING_KEY="shop.trade";
    private final RabbitTemplate rabbit;

    /** 复用应用启用 correlated confirm 和 mandatory 的 RabbitTemplate。 */
    public ShopTradeEventSender(RabbitTemplate rabbit) { this.rabbit=rabbit; }

    /** 使用固定消息 ID 和每次不同的相关 ID，发送后最多等待五秒确认。 */
    public void send(ShopTradeEvent event) throws Exception {
        CorrelationData correlation=new CorrelationData(event.getLeaseToken());
        // 只有 ORDER_REQUEST 走库存热点队列；其他状态事件走交易通知队列。消息体只含事件 ID。
        rabbit.convertAndSend(EXCHANGE,"ORDER_REQUEST".equals(event.getEventType())?"shop.order":ROUTING_KEY,event.getId(),message->{
            message.getMessageProperties().setMessageId(event.getId());
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        },correlation);
        CorrelationData.Confirm confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if(!confirm.isAck()) throw new IllegalStateException("BROKER_NACK");
        if(correlation.getReturned()!=null) throw new IllegalStateException("UNROUTABLE_RETURN");
    }
}
