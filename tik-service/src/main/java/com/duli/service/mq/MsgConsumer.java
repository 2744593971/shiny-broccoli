package com.duli.service.mq;

import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.enums.MessageEnum;
import com.duli.service.MsgService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MsgConsumer {

    @Autowired
    private MsgService msgService;
//@RabbitListener(queues = RabbitMQConfig.QUEUE_SYS_MSG) 注解，
// 它死死盯住你配置好的系统消息队列。只要队列里被交换机塞进了新消息，这个方法就会瞬间被触发。
    @RabbitListener(queues = RabbitMQConfig.QUEUE_SYS_MSG)
    public void watchSysMsgQueue(MessageMQDTO payload) {
        System.out.println("MQ 消费者接收到消息，准备存入 MongoDB...");
        
        // 把 DTO 里的 Integer type 还原回 MessageEnum
        MessageEnum currentEnum = null;
        for (MessageEnum e : MessageEnum.values()) {
            if (e.type.equals(payload.getMsgType())) {
                currentEnum = e;
                break;
            }
        }

        if (currentEnum != null) {
            // 调用原有的 MongoDB 写入逻辑
            msgService.createMsg(
                    payload.getFromUserId(),
                    payload.getToUserId(),
                    currentEnum,
                    payload.getMsgContent()
            );
        }
    }
}