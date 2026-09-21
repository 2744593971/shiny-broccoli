package com.duli.service.mq;

import com.duli.mapper.ShopTradeEventRepository;
import com.duli.pojo.ShopTradeEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.*;
import java.util.UUID;

/** 网络发送始终在订单事务外执行，进程崩溃后凭数据库租约恢复。 */
@Service
public class ShopTradeEventPublisher {
    private static final Logger log=LoggerFactory.getLogger(ShopTradeEventPublisher.class);
    private final ShopTradeEventRepository repository;
    private final ShopTradeEventSender sender;

    /** 注入持久化事件访问层与确认发送器。 */
    public ShopTradeEventPublisher(ShopTradeEventRepository repository,ShopTradeEventSender sender) {
        this.repository=repository;this.sender=sender;
    }

    /** 扫描已提交且到期的事件，发送失败保留记录并进行有限退避。 */
    public void publishBatch() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cannot publish inside a business transaction");
        repository.expireLeases();
        for(String id:repository.dueIds()) {
            ShopTradeEvent event=repository.claim(id,UUID.randomUUID().toString().replace("-",""));
            if(event==null) continue;
            try {
                sender.send(event);repository.sent(event);
            } catch(Exception error) {
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                repository.failed(event,error.getClass().getSimpleName());
                log.warn("交易消息发送失败 eventId={} attempt={} errorType={}",id,event.getAttempts(),error.getClass().getSimpleName());
                // 首次发送失败后结束本批，避免断网时逐条等待确认造成长时间阻塞。
                return;
            }
        }
    }
}
