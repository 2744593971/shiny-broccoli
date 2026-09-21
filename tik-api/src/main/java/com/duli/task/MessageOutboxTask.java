package com.duli.task;
import com.duli.service.mq.MessageOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

/** ==================== Codex 优化：定时投递和失败可观测性；无需修改前端接口 ==================== */
@Component
public class MessageOutboxTask {
    private static final Logger log=LoggerFactory.getLogger(MessageOutboxTask.class);
    private final MessageOutboxPublisher publisher;
    public MessageOutboxTask(MessageOutboxPublisher publisher) {this.publisher=publisher;}
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    public void publish() {
        try { publisher.publishBatch(); }
        catch(Exception error) {
            log.error("Codex优化 Outbox批次失败，下轮重试；检查数据库及建表脚本。errorType={}",error.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void reportFailures() {
        try {
            long count=publisher.failedCount();
            if(count>0) log.error("Codex优化 Outbox存在 {} 条FAILED消息，需按消息可靠性说明人工检查/重放",count);
        } catch(Exception error) { log.warn("Outbox失败统计不可用：{}",error.getClass().getSimpleName()); }
    }
}
