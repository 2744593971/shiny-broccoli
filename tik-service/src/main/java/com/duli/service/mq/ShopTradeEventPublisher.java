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

    /**
     * 在业务事务外扫描 Outbox：回收过期租约、逐条抢占、等待 Broker Confirm/Return，
     * 然后标记 SENT；失败时保存异常类型和下次重试时间。MQ 发出不代表业务已消费，
     * 真正消费完成要看 shop_trade_receipt。
     */
    public void publishBatch() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cannot publish inside a business transaction");
        // 先回收崩溃发送者留下的过期租约，再逐条抢占待发送事件。
        repository.expireLeases();
        for(String id:repository.dueIds()) {
            ShopTradeEvent event=repository.claim(id,UUID.randomUUID().toString().replace("-",""));
            if(event==null) continue;
            try {
                // 仅 Broker ACK 且未被 Return 才标记 SENT；消费成功由另一张回执表确认。
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
