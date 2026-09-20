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
            // 调用你原有的 MongoDB 写入逻辑
            msgService.createMsg(
                    payload.getFromUserId(),
                    payload.getToUserId(),
                    currentEnum,
                    payload.getMsgContent()
            );
        }
    }
}