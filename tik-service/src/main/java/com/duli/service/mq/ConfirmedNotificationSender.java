package com.duli.service.mq;
import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.pojo.MessageOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** ==================== Codex 优化：ACK + mandatory Return 同时检查，不能把“调用没异常”当发送成功 ==================== */
@Component
public class ConfirmedNotificationSender {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    public ConfirmedNotificationSender(RabbitTemplate rabbit,ObjectMapper json) {this.rabbit=rabbit;this.json=json;}
    public void send(MessageOutbox row) throws Exception {
        MessageMQDTO event=json.readValue(row.getPayload(),MessageMQDTO.class);
        if(!row.getId().equals(event.getEventId())) throw new IllegalArgumentException("OUTBOX_EVENT_ID_MISMATCH");
        // 每次投递 CorrelationData 都不同；messageId/eventId 不变，供后续消费幂等使用。
        CorrelationData correlation=new CorrelationData(row.getLeaseToken());
        rabbit.convertAndSend(RabbitMQConfig.EXCHANGE_MSG,row.getRoutingKey(),event,message->{
            message.getMessageProperties().setMessageId(row.getId());
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        },correlation);
        CorrelationData.Confirm confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        // Spring AMQP 在返回消息存在时，先填充 returned 再完成 confirm Future。
        if(!confirm.isAck()) throw new IllegalStateException("BROKER_NACK");
        if(correlation.getReturned()!=null) throw new IllegalStateException("UNROUTABLE_RETURN");
    }
}
