package com.duli.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    //产生消息的业务叫生产者 然后消息业务msgservice是消费者，msg是由交换机控制队列
    public static final String EXCHANGE_MSG = "exchange_msg";//交换机
    public static final String QUEUE_SYS_MSG = "queue_sys_msg";//队列
    public static final String ROUTING_KEY_MSG = "sys.msg.#";// 定义路由规则（requestMapping）
    // 核心作用是告诉交换机该把哪些消息放进这个特定队列

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