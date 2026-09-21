package com.duli.service.mq;
import com.duli.mapper.MessageOutboxRepository;
import com.duli.pojo.MessageOutbox;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** ==================== Codex 优化：只扫描已提交记录，后台补发；业务请求不依赖 RabbitMQ 是否在线 ==================== */
@Service
public class MessageOutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(MessageOutboxPublisher.class);
    private final MessageOutboxRepository repository;
    private final ConfirmedNotificationSender sender;
    public MessageOutboxPublisher(MessageOutboxRepository repository,ConfirmedNotificationSender sender) {
        this.repository=repository;this.sender=sender;
    }
    public void publishBatch() {
        // ==================== Codex 优化：禁止从未提交的业务事务里提前投递 ====================
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox publisher must run outside business transactions");
        repository.expireExhausted();
        for(String id:repository.dueIds()) {
            MessageOutbox row=repository.claim(id,UUID.randomUUID().toString().replace("-",""));
            if(row==null) continue;
            try {
                sender.send(row);
                repository.sent(row);
            } catch(Exception error) {
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                // ACK 丢失或发送后数据库更新失败时允许重投；不能直接宣称 exactly-once。
                repository.failed(row,error.getClass().getSimpleName());
                log.warn("Codex优化 Outbox发送未确认 eventId={} attempt={} errorType={}",id,row.getAttempts(),error.getClass().getSimpleName());
                if(Thread.currentThread().isInterrupted()) return;
            }
        }
    }
    public long failedCount() { return repository.failedCount(); }
}
