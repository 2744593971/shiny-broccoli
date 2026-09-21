package com.duli.service;
import com.duli.dto.MessageMQDTO;
/** ==================== Codex 优化：必须在业务数据库事务内记录通知 ==================== */
public interface IMessageOutboxService {
    void enqueue(String exchange,String routingKey,MessageMQDTO event);
}
