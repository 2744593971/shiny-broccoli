package com.duli.service;

import com.duli.service.impl.*;
import com.duli.mapper.ShopTradeRepository;
import com.duli.pojo.*;
import com.duli.bo.*;
import com.duli.exceptions.ShopException;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 复用秒杀测试的独立内存数据库夹具，并再次运行继承的库存回归。
 * 这里是真 SQL/事务/并发测试；模拟支付不会访问任何外部支付网络。
 */
class ShopTradeServiceTest extends ShopServiceTest {
    ShopTradeServiceImpl trade;
    ShopTradeRepository tradeRepository;
    MockPaymentGateway gateway;
    @BeforeEach void setupTrade() {
        tradeRepository=new ShopTradeRepository(jdbc);
        gateway=new MockPaymentGateway("mock",new MockEnvironment());
        trade=new ShopTradeServiceImpl(repository,tradeRepository,gateway,manager,"admin",new com.duli.mapper.ShopTradeEventRepository(jdbc));
    }
    ShopOrder create() { return service.place("u",input("a","request_trade_0001")); }
    ShopPaymentBO payInput(String id,String channel) {
        ShopPaymentBO p=new ShopPaymentBO();p.setOrderId(id);p.setChannel(channel);return p;
    }
    ShopOrder paid() {
        ShopOrder order=create();
        trade.payment("u",payInput(order.getId(),"WECHAT"));
        return trade.confirmTest("u",order.getId());
    }
    ShopShipmentBO shipment(String id) {
        ShopShipmentBO s=new ShopShipmentBO();s.setOrderId(id);s.setCarrier("测试快递");s.setTrackingNo("TEST12345678");return s;
    }
    @Test void threeChannelsCompleteWithoutRealMoney() {
        for(String channel:Arrays.asList("WECHAT","ALIPAY","BANK_CARD")) {
            ShopOrder order=service.place("u",input(null,"request_channel_"+channel));
            assertEquals("WAIT_PAY",order.getStatus());
            assertNotNull(order.getReceiverAddress());
            assertTrue(order.getExpiresAt()>order.getCreatedAt());
            Map<String,Object> prepared=trade.payment("u",payInput(order.getId(),channel));
            assertEquals(true,prepared.get("testOnly"));
            ShopOrder result=trade.confirmTest("u",order.getId());
            assertEquals("PAID",result.getStatus());assertEquals(channel,result.getPaymentChannel());
            assertTrue(tradeRepository.payment(order.getId()).getExternalTradeId().startsWith("mock_"));
            assertEquals("PAID",trade.confirmTest("u",order.getId()).getStatus());
        }
        assertEquals(3,number("SELECT COUNT(*) FROM shop_payment"));
        assertEquals(47,number("SELECT stock FROM shop_product"));
    }
    @Test void prepareIsIdempotentAndCanSwitchMockChannel() {
        ShopOrder order=create();
        trade.payment("u",payInput(order.getId(),"WECHAT"));
        String id=tradeRepository.payment(order.getId()).getId();
        trade.payment("u",payInput(order.getId(),"ALIPAY"));
        assertEquals(id,tradeRepository.payment(order.getId()).getId());
        assertEquals("ALIPAY",tradeRepository.payment(order.getId()).getChannel());
        assertEquals(1,number("SELECT COUNT(*) FROM shop_payment"));
    }
    @Test void cancellationRestoresBothStocksExactlyOnce() {
        ShopOrder order=create();
        trade.payment("u",payInput(order.getId(),"BANK_CARD"));
        assertEquals("CANCELLED",trade.cancel("u",order.getId()).getStatus());
        trade.cancel("u",order.getId());trade.expireOrders();
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
        assertEquals("CLOSED",tradeRepository.payment(order.getId()).getStatus());
        assertThrows(ShopException.class,()->trade.confirmTest("u",order.getId()));
        // 已取消的同场订单保留限购约束，不允许通过反复占库存绕过一人一场。
        assertEquals(order.getId(),service.place("u",input("a","request_after_cancel")).getId());
    }
    @Test void timeoutCannotBePaidAndReleasesOnlyOnce() {
        ShopOrder order=create();
        trade.payment("u",payInput(order.getId(),"ALIPAY"));
        jdbc.update("UPDATE shop_order SET expires_at=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1000),order.getId());
        assertThrows(ShopException.class,()->trade.confirmTest("u",order.getId()));
        trade.expireOrders();trade.expireOrders();
        assertEquals("EXPIRED",service.order("u",order.getId()).getStatus());
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
    }
    @Test void scheduledExpiryWorksWithoutPaymentAttempt() {
        ShopOrder order=create();
        jdbc.update("UPDATE shop_order SET expires_at=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1000),order.getId());
        trade.expireOrders();
        assertEquals("EXPIRED",service.order("u",order.getId()).getStatus());
        assertEquals(50,number("SELECT stock FROM shop_product"));
    }
    @Test void unpaidCannotShipOrRefundOrReceive() {
        ShopOrder order=create();
        assertThrows(ShopException.class,()->trade.ship("admin",shipment(order.getId())));
        assertThrows(ShopException.class,()->trade.refund("u",order.getId()));
        assertThrows(ShopException.class,()->trade.receive("u",order.getId()));
    }
    @Test void refundBeforeShipmentIsIdempotent() {
        ShopOrder order=paid();
        assertEquals("REFUNDED",trade.refund("u",order.getId()).getStatus());
        trade.refund("u",order.getId());
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
        assertNotNull(tradeRepository.payment(order.getId()).getRefundId());
        assertThrows(ShopException.class,()->trade.ship("admin",shipment(order.getId())));
    }
    @Test void shippingRequiresAdminAndReceivingRequiresOwner() {
        ShopOrder order=paid();
        assertEquals(403,assertThrows(ShopException.class,()->trade.ship("u",shipment(order.getId()))).getCode());
        assertEquals(403,assertThrows(ShopException.class,()->trade.adminOrders("u",1,12)).getCode());
        ShopOrder shipped=trade.ship("admin",shipment(order.getId()));
        assertEquals("SHIPPED",shipped.getStatus());assertEquals("TEST12345678",shipped.getTrackingNo());
        assertEquals("SHIPPED",trade.ship("admin",shipment(order.getId())).getStatus());
        assertThrows(ShopException.class,()->trade.refund("u",order.getId()));
        assertThrows(ShopException.class,()->trade.receive("other",order.getId()));
        assertEquals("COMPLETED",trade.receive("u",order.getId()).getStatus());
        assertEquals("COMPLETED",trade.receive("u",order.getId()).getStatus());
        assertEquals(49,number("SELECT stock FROM shop_product"));
    }
    @Test void buyerCannotOperateOtherBuyersOrder() {
        ShopOrder order=create();
        assertThrows(ShopException.class,()->trade.payment("other",payInput(order.getId(),"WECHAT")));
        assertThrows(ShopException.class,()->trade.confirmTest("other",order.getId()));
        assertThrows(ShopException.class,()->trade.cancel("other",order.getId()));
        assertThrows(ShopException.class,()->service.orderByRequest("other",order.getRequestId()));
        assertEquals(order.getId(),service.orderByRequest("u",order.getRequestId()).getId());
    }
    @Test void malformedChannelAndCorruptedAmountRejected() {
        ShopOrder order=create();
        assertThrows(ShopException.class,()->trade.payment("u",payInput(order.getId(),"FAKE")));
        trade.payment("u",payInput(order.getId(),"WECHAT"));
        jdbc.update("UPDATE shop_payment SET amount=0 WHERE order_id=?",order.getId());
        assertThrows(ShopException.class,()->trade.confirmTest("u",order.getId()));
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
    }
    @Test void prodAndDisabledModesCannotSimulatePayment() {
        MockEnvironment env=new MockEnvironment();env.setActiveProfiles("prod");
        MockPaymentGateway prod=new MockPaymentGateway("mock",env);
        assertFalse(prod.testEnabled());assertFalse(new MockPaymentGateway("disabled",new MockEnvironment()).testEnabled());
        ShopTradeServiceImpl blocked=new ShopTradeServiceImpl(repository,tradeRepository,prod,manager,"",new com.duli.mapper.ShopTradeEventRepository(jdbc));
        ShopOrder order=create();
        assertThrows(ShopException.class,()->blocked.payment("u",payInput(order.getId(),"WECHAT")));
        assertThrows(ShopException.class,()->blocked.confirmTest("u",order.getId()));
        assertEquals(false,blocked.config("admin").get("admin"));
    }
    @Test void failingGatewayDoesNotMarkPaid() {
        ShopOrder order=create();
        trade.payment("u",payInput(order.getId(),"WECHAT"));
        IPaymentGateway broken=mock(IPaymentGateway.class);
        when(broken.testEnabled()).thenReturn(true);
        when(broken.confirmTest(any())).thenThrow(new IllegalStateException("simulated failure"));
        ShopTradeServiceImpl brokenTrade=new ShopTradeServiceImpl(repository,tradeRepository,broken,manager,"admin",new com.duli.mapper.ShopTradeEventRepository(jdbc));
        assertThrows(IllegalStateException.class,()->brokenTrade.confirmTest("u",order.getId()));
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
        assertEquals("PENDING",tradeRepository.payment(order.getId()).getStatus());
    }
    @Test void simultaneousPayAndCancelHaveOneWinner() throws Exception {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"WECHAT"));
        together(()->trade.confirmTest("u",order.getId()),()->trade.cancel("u",order.getId()));
        ShopOrder result=service.order("u",order.getId());
        assertTrue(Arrays.asList("PAID","CANCELLED").contains(result.getStatus()));
        boolean paid="PAID".equals(result.getStatus());
        assertEquals(paid?49:50,number("SELECT stock FROM shop_product"));
        assertEquals(paid?"SUCCEEDED":"CLOSED",tradeRepository.payment(order.getId()).getStatus());
    }
    @Test void simultaneousRefundAndShipHaveOneWinner() throws Exception {
        ShopOrder order=paid();
        together(()->trade.refund("u",order.getId()),()->trade.ship("admin",shipment(order.getId())));
        String status=service.order("u",order.getId()).getStatus();
        assertTrue(Arrays.asList("REFUNDED","SHIPPED").contains(status));
        assertEquals("REFUNDED".equals(status)?50:49,number("SELECT stock FROM shop_product"));
    }
    @Test void simultaneousRefundsOnlyRestoreOnce() throws Exception {
        ShopOrder order=paid();
        together(()->trade.refund("u",order.getId()),()->trade.refund("u",order.getId()));
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
    }
    @Test void refundFailureRollsBackPaymentOrderAndStocks() {
        ShopOrder order=paid();
        ShopTradeRepository broken=spy(tradeRepository);
        doAnswer(call->{
            call.callRealMethod();
            throw new IllegalStateException("simulate failure after SQL changes");
        }).when(broken).refund(any(),any(),anyString());
        ShopTradeServiceImpl service=new ShopTradeServiceImpl(repository,broken,gateway,manager,"admin",new com.duli.mapper.ShopTradeEventRepository(jdbc));
        assertThrows(IllegalStateException.class,()->service.refund("u",order.getId()));
        assertEquals("PAID",repository.order(order.getId(),"u").getStatus());
        assertEquals("SUCCEEDED",tradeRepository.payment(order.getId()).getStatus());
        assertEquals(49,number("SELECT stock FROM shop_product"));
        assertEquals(4,number("SELECT stock FROM shop_activity"));
    }
    void together(Callable<ShopOrder> first,Callable<ShopOrder> second) throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<ShopOrder>> futures=new ArrayList<>();
            for(Callable<ShopOrder> call:Arrays.asList(first,second)) futures.add(executor.submit(()->{
                start.await();try{return call.call();}catch(ShopException e){if(e.getCode()!=409)throw e;return null;}
            }));
            start.countDown();
            int successes=0;for(Future<ShopOrder> future:futures)if(future.get(15,TimeUnit.SECONDS)!=null)successes++;
            assertTrue(successes>=1);
        } finally { executor.shutdownNow(); }
    }

    /** 重复回调只生成一条回执和一条支付事件，退款后再回调也不能复活订单。 */
    @Test void callbackIsIdempotentIncludingAfterRefund() {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"WECHAT"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
        assertEquals("PAID",trade.callback("u",callback).getStatus());
        assertEquals("PAID",trade.callback("u",callback).getStatus());
        assertEquals(1,number("SELECT COUNT(*) FROM shop_payment_callback"));
        assertEquals(1,number("SELECT COUNT(*) FROM shop_trade_event WHERE event_type='PAYMENT_SUCCEEDED'"));
        trade.refund("u",order.getId());trade.refund("u",order.getId());
        assertEquals("REFUNDED",trade.callback("u",callback).getStatus());
        assertEquals(1,number("SELECT COUNT(*) FROM shop_refund"));
        assertEquals("SUCCEEDED",tradeRepository.refundRecord(order.getId()).getStatus());
        assertEquals(50,number("SELECT stock FROM shop_product"));
    }

    /** 回调验签、归属、金额、渠道任一不匹配都不能改变订单。 */
    @Test void callbackRejectsTamperingAndWrongOwner() {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"ALIPAY"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        assertThrows(ShopException.class,()->trade.callback("other",callback));
        callback.setAmount(new java.math.BigDecimal("0.01"));
        assertEquals(403,assertThrows(ShopException.class,()->trade.callback("u",callback)).getCode());
        ShopPayment fake=tradeRepository.payment(order.getId());
        fake.setAmount(new java.math.BigDecimal("0.01"));
        assertEquals(409,assertThrows(ShopException.class,()->trade.callback("u",
                gateway.notification(fake,gateway.confirmTest(fake)))).getCode());
        fake.setAmount(order.getAmount());fake.setChannel("BANK_CARD");
        assertEquals(409,assertThrows(ShopException.class,()->trade.callback("u",
                gateway.notification(fake,gateway.confirmTest(fake)))).getCode());
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
        assertEquals(0,number("SELECT COUNT(*) FROM shop_payment_callback"));
    }

    /** 关闭后的迟到通知只留拒绝记录，不恢复支付状态、不重复回补库存。 */
    @Test void lateCallbackIsAuditedWithoutRevivingClosedOrder() {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"BANK_CARD"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        trade.cancel("u",order.getId());
        assertEquals("CANCELLED",trade.callback("u",callback).getStatus());
        trade.callback("u",callback);
        assertEquals("LATE_REJECTED",jdbc.queryForObject("SELECT outcome FROM shop_payment_callback",String.class));
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_trade_event WHERE event_type='PAYMENT_SUCCEEDED'"));
    }

    /** 到期边界由数据库裁决，即使定时任务尚未扫描也拒绝付款并释放库存。 */
    @Test void overdueCallbackClosesOrderAtomically() {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"WECHAT"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        jdbc.update("UPDATE shop_order SET expires_at=? WHERE id=?",new Timestamp(System.currentTimeMillis()-5000),order.getId());
        assertEquals("EXPIRED",trade.callback("u",callback).getStatus());
        assertEquals("LATE_REJECTED",jdbc.queryForObject("SELECT outcome FROM shop_payment_callback",String.class));
        assertEquals(50,number("SELECT stock FROM shop_product"));
    }

    /** 相同通知并发处理仍只有一个状态更新、回调记录和支付事件。 */
    @Test void concurrentCallbacksApplyOnce() throws Exception {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"WECHAT"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        together(()->trade.callback("u",callback),()->trade.callback("u",callback));
        assertEquals(1,number("SELECT COUNT(*) FROM shop_payment_callback"));
        assertEquals(1,number("SELECT COUNT(*) FROM shop_trade_event WHERE event_type='PAYMENT_SUCCEEDED'"));
        assertEquals(49,number("SELECT stock FROM shop_product"));
    }

    /** 回调回执保存失败时，订单、支付和 Outbox 全部回滚，重试后可成功。 */
    @Test void callbackReceiptFailureRollsBackStateAndEvent() {
        ShopOrder order=create();trade.payment("u",payInput(order.getId(),"WECHAT"));
        ShopPaymentCallbackBO callback=trade.mockNotification("u",order.getId());
        ShopTradeRepository broken=spy(tradeRepository);
        doThrow(new IllegalStateException("receipt unavailable")).when(broken)
                .callback(anyString(),anyString(),anyString(),anyString());
        ShopTradeServiceImpl failing=new ShopTradeServiceImpl(repository,broken,gateway,manager,"admin",eventRepository());
        assertThrows(IllegalStateException.class,()->failing.callback("u",callback));
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
        assertEquals("PENDING",tradeRepository.payment(order.getId()).getStatus());
        assertEquals(0,number("SELECT COUNT(*) FROM shop_trade_event WHERE event_type='PAYMENT_SUCCEEDED'"));
        assertEquals("PAID",trade.callback("u",callback).getStatus());
    }

    /** 没有 MQ 也能完成付款，业务事务仅持久化待发记录。 */
    @Test void brokerOfflineRetriesWithStableEventId() throws Exception {
        ShopOrder order=paid();
        com.duli.mapper.ShopTradeEventRepository events=eventRepository();
        com.duli.service.mq.ShopTradeEventSender sender=mock(com.duli.service.mq.ShopTradeEventSender.class);
        doThrow(new IllegalStateException("offline")).doNothing().when(sender).send(any());
        com.duli.service.mq.ShopTradeEventPublisher publisher=new com.duli.service.mq.ShopTradeEventPublisher(events,sender);
        String id=eventId("PAYMENT_SUCCEEDED",order.getId());
        trade.consumeEvent(eventId("ORDER_CREATED",order.getId()));
        publisher.publishBatch();
        assertEquals("PENDING",events.find(id).getStatus());
        assertEquals("PAID",service.order("u",order.getId()).getStatus());
        jdbc.update("UPDATE shop_trade_event SET next_attempt_at=? WHERE id=?",new Timestamp(0),id);
        publisher.publishBatch();
        assertEquals("SENT",events.find(id).getStatus());
        org.mockito.ArgumentCaptor<ShopTradeEvent> sent=org.mockito.ArgumentCaptor.forClass(ShopTradeEvent.class);
        verify(sender,times(2)).send(sent.capture());
        assertEquals(id,sent.getAllValues().get(0).getId());
        assertEquals(id,sent.getAllValues().get(1).getId());
        assertNotEquals(sent.getAllValues().get(0).getLeaseToken(),sent.getAllValues().get(1).getLeaseToken());
    }

    /** 消费同一关单事件两次只回补一次库存，回执与业务一致。 */
    @Test void expiryMessageIsIdempotentAndCannotCloseEarly() {
        ShopOrder order=create();String id=eventId("ORDER_EXPIRE",order.getId());
        assertFalse(eventRepository().dueIds().contains(id));
        assertThrows(ShopException.class,()->trade.consumeEvent(id));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_trade_receipt"));
        jdbc.update("UPDATE shop_order SET expires_at=? WHERE id=?",new Timestamp(0),order.getId());
        trade.consumeEvent(id);trade.consumeEvent(id);trade.expireOrders();
        assertEquals("EXPIRED",service.order("u",order.getId()).getStatus());
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(1,number("SELECT COUNT(*) FROM shop_trade_receipt"));
        assertNotNull(eventRepository().find(id).getConsumedAt());
    }

    /** 支付后的关单消息只记录消费结果，不能把已支付订单关闭。 */
    @Test void expiryMessageAfterPaymentDoesNotReleaseStock() {
        ShopOrder order=paid();String id=eventId("ORDER_EXPIRE",order.getId());
        trade.consumeEvent(id);trade.consumeEvent(id);
        assertEquals("PAID",service.order("u",order.getId()).getStatus());
        assertEquals(49,number("SELECT stock FROM shop_product"));
    }

    /** 回执 SQL 失败时关单、库存和新增关闭事件均回滚，重投后安全完成。 */
    @Test void consumerReceiptFailureRollsBackBusiness() {
        ShopOrder order=create();String id=eventId("ORDER_EXPIRE",order.getId());
        jdbc.update("UPDATE shop_order SET expires_at=? WHERE id=?",new Timestamp(0),order.getId());
        com.duli.mapper.ShopTradeEventRepository broken=spy(eventRepository());
        doThrow(new IllegalStateException("receipt failure")).when(broken).receipt(any());
        ShopTradeServiceImpl failing=new ShopTradeServiceImpl(repository,tradeRepository,gateway,manager,"admin",broken);
        assertThrows(IllegalStateException.class,()->failing.consumeEvent(id));
        assertEquals("WAIT_PAY",service.order("u",order.getId()).getStatus());
        assertEquals(49,number("SELECT stock FROM shop_product"));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_trade_event WHERE event_type='ORDER_CLOSED'"));
        trade.consumeEvent(id);
        assertEquals(50,number("SELECT stock FROM shop_product"));
    }

    /** 消费失败事件仅管理员可重放，消费成功后即使旧死信重投也不重复执行。 */
    @Test void deadEventCanBeReplayedOnlyByAdmin() {
        ShopOrder order=paid();String id=eventId("PAYMENT_SUCCEEDED",order.getId());
        com.duli.mapper.ShopTradeEventRepository events=eventRepository();events.dead(id);
        assertEquals(1,trade.failedEvents("admin").size());
        assertThrows(ShopException.class,()->trade.replayEvent("u",id));
        assertTrue(trade.replayEvent("admin",id));
        trade.consumeEvent(id);events.dead(id);
        assertFalse(trade.replayEvent("admin",id));
        assertEquals(1,number("SELECT COUNT(*) FROM shop_trade_receipt"));
    }

    /** 发送确认后长期无回执也必须可观察和人工恢复。 */
    @Test void missingConsumerReceiptBecomesRecoverableFailure() {
        ShopOrder order=paid();String id=eventId("PAYMENT_SUCCEEDED",order.getId());
        com.duli.mapper.ShopTradeEventRepository events=eventRepository();
        ShopTradeEvent claim=events.claim(id,"lease");events.sent(claim);
        jdbc.update("UPDATE shop_trade_event SET published_at=? WHERE id=?",new Timestamp(0),id);
        events.expireLeases();
        assertEquals("FAILED",events.find(id).getStatus());
        assertEquals(1,trade.failedEvents("admin").size());
        assertTrue(trade.replayEvent("admin",id));
    }

    /** 第十次发送崩溃后转为失败，旧租约不能覆盖新租约的处理结果。 */
    @Test void leaseRecoveryAndBoundedRetries() {
        ShopOrder order=paid();String id=eventId("PAYMENT_SUCCEEDED",order.getId());
        com.duli.mapper.ShopTradeEventRepository events=eventRepository();
        ShopTradeEvent old=events.claim(id,"old");
        jdbc.update("UPDATE shop_trade_event SET lease_until=? WHERE id=?",new Timestamp(0),id);
        ShopTradeEvent fresh=events.claim(id,"fresh");assertNotNull(fresh);
        events.sent(old);assertEquals("IN_FLIGHT",events.find(id).getStatus());
        events.failed(old,"old");assertEquals("IN_FLIGHT",events.find(id).getStatus());
        jdbc.update("UPDATE shop_trade_event SET attempts=10,lease_until=? WHERE id=?",new Timestamp(0),id);
        events.expireLeases();assertEquals("FAILED",events.find(id).getStatus());
        assertNull(events.claim(id,"again"));
    }

    /** 退款事务故障不会留下成功退款单或退款事件。 */
    @Test void refundEventFailureRollsBackEverything() {
        ShopOrder order=paid();
        com.duli.mapper.ShopTradeEventRepository broken=spy(eventRepository());
        doThrow(new IllegalStateException("outbox unavailable")).when(broken)
                .enqueue(eq("REFUND_SUCCEEDED"),eq(order.getId()),anyLong());
        ShopTradeServiceImpl failing=new ShopTradeServiceImpl(repository,tradeRepository,gateway,manager,"admin",broken);
        assertThrows(IllegalStateException.class,()->failing.refund("u",order.getId()));
        assertNull(tradeRepository.refundRecord(order.getId()));
        assertEquals("PAID",service.order("u",order.getId()).getStatus());
        assertEquals("SUCCEEDED",tradeRepository.payment(order.getId()).getStatus());
        assertEquals(49,number("SELECT stock FROM shop_product"));
    }

    /** 支付与退款详情不能被其他用户查看，Mock 通知也不能跨用户生成。 */
    @Test void paymentDetailsRespectOwnership() {
        ShopOrder order=paid();
        assertThrows(ShopException.class,()->trade.paymentDetail("other",order.getId()));
        assertThrows(ShopException.class,()->trade.mockNotification("other",order.getId()));
        Map<String,Object> details=trade.paymentDetail("u",order.getId());
        assertNotNull(details.get("payment"));assertNull(details.get("refund"));
        assertEquals(1,((List<?>)details.get("callbacks")).size());
    }

    /** 下单写入到期事件失败时库存和订单同时回滚，防止只创建业务不保存事件。 */
    @Test void orderEventFailureRollsBackStock() {
        com.duli.mapper.ShopTradeEventRepository broken=mock(com.duli.mapper.ShopTradeEventRepository.class);
        doThrow(new IllegalStateException("outbox failure")).when(broken).enqueue(anyString(),anyString(),anyLong());
        ShopServiceImpl failing=new ShopServiceImpl(repository,manager,broken);
        assertThrows(IllegalStateException.class,()->failing.place("u",input("a","request_event_failure")));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
        assertEquals(50,number("SELECT stock FROM shop_product"));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
    }

    /** 获取测试数据库对应的事件访问层，所有测试不连接业务数据库。 */
    private com.duli.mapper.ShopTradeEventRepository eventRepository() {
        return new com.duli.mapper.ShopTradeEventRepository(jdbc);
    }

    /** 查找本测试订单的指定事件 ID，避免依赖随机 UUID。 */
    private String eventId(String type,String orderId) {
        return jdbc.queryForObject("SELECT id FROM shop_trade_event WHERE event_type=? AND order_id=?",String.class,type,orderId);
    }
}
