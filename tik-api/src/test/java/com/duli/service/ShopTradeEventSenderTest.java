package com.duli.service;

import com.duli.pojo.ShopTradeEvent;
import com.duli.service.mq.ShopTradeEventSender;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 在 RabbitTemplate 边界模拟 Broker 确认，不宣称替代真实网络故障集成测试。 */
class ShopTradeEventSenderTest {
    private RabbitTemplate rabbit;
    private ShopTradeEventSender sender;
    private ShopTradeEvent event;

    /** 创建不访问网络的发送器夹具。 */
    @BeforeEach void setup() {
        rabbit=mock(RabbitTemplate.class);sender=new ShopTradeEventSender(rabbit);
        event=new ShopTradeEvent();event.setId("event1");event.setLeaseToken("lease1");
    }

    /** 模拟协议回调，同时检查持久化属性和消息关联 ID。 */
    private void broker(boolean ack,boolean returned) {
        doAnswer(call->{
            assertEquals("event1",call.getArgument(2));
            MessagePostProcessor processor=call.getArgument(3);
            Message message=processor.postProcessMessage(new Message(new byte[0],new MessageProperties()));
            assertEquals("event1",message.getMessageProperties().getMessageId());
            assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());
            CorrelationData correlation=call.getArgument(4);
            assertEquals("lease1",correlation.getId());
            if(returned) correlation.setReturned(new ReturnedMessage(message,312,"NO_ROUTE",
                    ShopTradeEventSender.EXCHANGE,ShopTradeEventSender.ROUTING_KEY));
            correlation.getFuture().set(new CorrelationData.Confirm(ack,ack?null:"failed"));
            return null;
        }).when(rabbit).convertAndSend(eq(ShopTradeEventSender.EXCHANGE),eq(ShopTradeEventSender.ROUTING_KEY),
                any(Object.class),any(MessagePostProcessor.class),any(CorrelationData.class));
    }

    /** Broker ACK 且无退回才允许调用方标记已发送。 */
    @Test void ackWithoutReturnSucceeds() throws Exception {
        broker(true,false);assertDoesNotThrow(()->sender.send(event));
    }

    /** Broker NACK 必须抛出异常以触发 Outbox 补发。 */
    @Test void nackIsFailure() {
        broker(false,false);
        assertEquals("BROKER_NACK",assertThrows(IllegalStateException.class,()->sender.send(event)).getMessage());
    }

    /** 即使 ACK，无路由退回也不算成功。 */
    @Test void returnOverridesAck() {
        broker(true,true);
        assertEquals("UNROUTABLE_RETURN",assertThrows(IllegalStateException.class,()->sender.send(event)).getMessage());
    }

    /** ACK 丢失时有限等待后失败，调用方保留原事件进行重投。 */
    @Test void missingConfirmTimesOut() {
        assertThrows(TimeoutException.class,()->sender.send(event));
    }

    /** 连接失败直接传播给 Outbox，不能静默吞掉。 */
    @Test void connectionFailurePropagates() {
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("offline")))
                .when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),
                        any(MessagePostProcessor.class),any(CorrelationData.class));
        assertThrows(org.springframework.amqp.AmqpConnectException.class,()->sender.send(event));
    }
}
