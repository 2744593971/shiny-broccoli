package com.duli.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_MSG = "exchange_msg";
    public static final String QUEUE_SYS_MSG = "queue_sys_msg";
    public static final String ROUTING_KEY_MSG = "sys.msg.#";

    // 1. 定义交换机
    @Bean
    public Exchange msgExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_MSG).durable(true).build();
    }

    // 2. 定义队列
    @Bean
    public Queue msgQueue() {
        return new Queue(QUEUE_SYS_MSG);
    }

    // 3. 绑定交换机和队列
    @Bean
    public Binding msgBinding(Queue msgQueue, Exchange msgExchange) {
        return BindingBuilder.bind(msgQueue).to(msgExchange).with(ROUTING_KEY_MSG).noargs();
    }
}