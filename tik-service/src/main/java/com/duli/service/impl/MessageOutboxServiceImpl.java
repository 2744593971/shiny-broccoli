package com.duli.service.impl;
import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.enums.MessageEnum;
import com.duli.mapper.MessageOutboxRepository;
import com.duli.service.IMessageOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
/** ==================== Codex 优化：Outbox 与业务写入使用同一个 MySQL 事务 ==================== */
@Service
public class MessageOutboxServiceImpl implements IMessageOutboxService {
    private final MessageOutboxRepository repository;
    private final ObjectMapper json;
    public MessageOutboxServiceImpl(MessageOutboxRepository repository,ObjectMapper json) {this.repository=repository;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void enqueue(String exchange,String routingKey,MessageMQDTO event) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Outbox requires a database transaction");
        if(!RabbitMQConfig.EXCHANGE_MSG.equals(exchange)||routingKey==null||!routingKey.matches("sys\\.msg\\.[a-z]+"))
            throw new IllegalArgumentException("Invalid notification route");
        if(event==null||event.getFromUserId()==null||event.getToUserId()==null
                ||Arrays.stream(MessageEnum.values()).noneMatch(type->type.type.equals(event.getMsgType())))
            throw new IllegalArgumentException("Invalid notification payload");
        event.setEventId(UUID.randomUUID().toString().replace("-",""));
        event.setOccurredAt(repository.now());
        try {
            String payload=json.writeValueAsString(event);
            if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072) throw new IllegalArgumentException("Notification too large");
            repository.insert(event.getEventId(),routingKey,payload);
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Notification serialization failed",error);
        }
        // 此处绝不调用 RabbitMQ；事务回滚会同时删除业务记录和这条待发记录。
    }
}
