package com.duli.config;

import com.duli.mapper.ShopTradeEventRepository;
import com.duli.service.mq.ShopTradeEventSender;
import org.springframework.amqp.core.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.*;
import java.nio.charset.StandardCharsets;
import org.slf4j.*;

/** 交易独立队列与死信拓扑；不修改现有社交通知队列的参数。 */
@Configuration
public class ShopTradeRabbitConfig {
    public static final String DEAD_EXCHANGE="shop.trade.dead.exchange";
    public static final String DEAD_QUEUE="shop.trade.dead.queue";
    private static final Logger log=LoggerFactory.getLogger(ShopTradeRabbitConfig.class);

    /** 一次声明持久化交换机、交易队列、死信交换机和死信队列。 */
    @Bean
    public Declarables shopTradeTopology() {
        DirectExchange exchange=new DirectExchange(ShopTradeEventSender.EXCHANGE,true,false);
        DirectExchange dead=new DirectExchange(DEAD_EXCHANGE,true,false);
        Queue queue=QueueBuilder.durable(ShopTradeEventSender.QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey("dead").build();
        Queue dlq=QueueBuilder.durable(DEAD_QUEUE).build();
        Queue orders=QueueBuilder.durable("shop.order.queue")
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey("dead").build();
        return new Declarables(exchange,dead,queue,dlq,orders,
                BindingBuilder.bind(orders).to(exchange).with("shop.order"),
                BindingBuilder.bind(queue).to(exchange).with(ShopTradeEventSender.ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dead).with("dead"));
    }

    /** 仅交易消费者使用的三次重试策略；耗尽后保存故障标记并拒绝重新入队。 */
    @Bean
    public SimpleRabbitListenerContainerFactory shopTradeListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory,
            ShopTradeEventRepository events) {
        return listenerFactory(configurer,connectionFactory,events,4);
    }

    /** 下单消费上限固定为两个线程，每线程最多使用两个数据库连接。 */
    @Bean
    public SimpleRabbitListenerContainerFactory shopOrderListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory,
            ShopTradeEventRepository events) {
        return listenerFactory(configurer,connectionFactory,events,2);
    }

    /** 创建互不共享的监听容器配置，限制并发和预取，避免挤占数据库连接池。 */
    private SimpleRabbitListenerContainerFactory listenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory,
            ShopTradeEventRepository events,int concurrency) {
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connectionFactory);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(4);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
                .backOffOptions(1000,2,4000).recoverer((message,cause)->{
                    String id=new String(message.getBody(),StandardCharsets.UTF_8);
                    try { events.dead(id); }
                    catch(Exception error) {
                        log.error("交易消息失败标记写入异常，检查死信队列并恢复数据库；errorType={}",error.getClass().getSimpleName());
                    }
                    log.error("交易消息消费重试耗尽，已拒绝并交由死信路由处理；messageId={}",
                            message.getMessageProperties().getMessageId());
                    throw new AmqpRejectAndDontRequeueException("Trade consumer exhausted",cause);
                }).build());
        return factory;
    }
}
