package com.duli.task;

import com.duli.mapper.ShopTradeEventRepository;
import com.duli.service.mq.ShopTradeEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

/** 交易事件补发和故障日志；不会阻止原超时关单扫描任务兜底。 */
@Component
public class ShopTradeEventTask {
    private static final Logger log=LoggerFactory.getLogger(ShopTradeEventTask.class);
    private final ShopTradeEventPublisher publisher;
    private final ShopTradeEventRepository events;

    /** 注入发布器和失败查询访问层。 */
    public ShopTradeEventTask(ShopTradeEventPublisher publisher,ShopTradeEventRepository events) {
        this.publisher=publisher;this.events=events;
    }

    /** 每批完成 250 毫秒后扫描，数据库不可用时下一轮继续。 */
    @Scheduled(fixedDelay=250,initialDelay=10000)
    public void publish() {
        try { publisher.publishBatch(); }
        catch(Exception error) { log.error("交易 Outbox 批次失败，请检查 004 支付迁移及数据库连接",error); }
    }

    /** 每分钟记录失败事件数；正式告警平台可采集此错误日志。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void failures() {
        try {
            int count=events.failures().size();
            if(count>0) log.error("交易 Outbox 存在未处理失败事件（最多显示100条） count={}，请检查 /shop/admin/payment-events 和死信队列",count);
        } catch(Exception error) { log.warn("交易事件故障统计失败 errorType={}",error.getClass().getSimpleName()); }
    }
}
