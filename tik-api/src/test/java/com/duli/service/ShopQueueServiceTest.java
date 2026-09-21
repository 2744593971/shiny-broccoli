package com.duli.service;

import com.duli.bo.ShopOrderBO;
import com.duli.pojo.*;
import com.duli.mapper.*;
import com.duli.service.impl.*;
import com.duli.exceptions.ShopException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立 H2 请求队列/库存事务测试，Redis 限流另测；不将本结果当作生产 QPS。 */
class ShopQueueServiceTest extends ShopServiceTest {
 private ShopOrderSubmissionService submissions;
 private ShopOrderRequestRepository requests;
 private ShopTradeEventRepository events;
 private ShopTradeServiceImpl trade;

 /** 复用基础库存夹具，MQ 消费由测试显式调用。 */
 @BeforeEach void queueSetup() {
  requests=new ShopOrderRequestRepository(jdbc);events=new ShopTradeEventRepository(jdbc);
  submissions=new ShopOrderSubmissionService(requests,events,service,mock(ShopAdmissionGuard.class),new ObjectMapper(),manager,10000);
  trade=new ShopTradeServiceImpl(repository,new ShopTradeRepository(jdbc),new MockPaymentGateway("mock",new MockEnvironment()),manager,"admin",events);
 }
 /** 查找请求/订单对应的权威事件。 */
 private String event(String type,String id) {
  return jdbc.queryForObject("SELECT id FROM shop_trade_event WHERE event_type=? AND order_id=?",String.class,type,id);
 }
 /** 受理阶段不扣库存；消息到达后生成订单，重复消费不产生第二张订单。 */
 @Test void acceptanceIsDurableAndConsumptionIsIdempotent() {
  ShopOrderRequest r=submissions.submit("u",input("a","request_queue_0001"));
  assertEquals("QUEUED",r.getStatus());assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
  assertEquals(5,number("SELECT stock FROM shop_activity"));
  assertEquals(r.getId(),submissions.submit("u",input("a","request_queue_0001")).getId());
  String id=event("ORDER_REQUEST",r.getId());submissions.consume(id);submissions.consume(id);
  ShopOrderRequest done=submissions.result("u",r.getRequestId());
  assertEquals("SUCCEEDED",done.getStatus());assertNull(done.getPayload());
  assertEquals(1,number("SELECT COUNT(*) FROM shop_order"));assertEquals(4,number("SELECT stock FROM shop_activity"));
 }
 /** 受理写事件失败时请求记录回滚，客户端可安全重试原编号。 */
 @Test void enqueueFailureDoesNotLeaveOrphanRequest() {
  ShopTradeEventRepository broken=spy(events);
  doThrow(new IllegalStateException("outbox unavailable")).when(broken).enqueue(anyString(),anyString(),anyLong());
  ShopOrderSubmissionService failing=new ShopOrderSubmissionService(requests,broken,service,mock(ShopAdmissionGuard.class),new ObjectMapper(),manager,10000);
  assertThrows(IllegalStateException.class,()->failing.submit("u",input("a","request_queue_0002")));
  assertEquals(0,number("SELECT COUNT(*) FROM shop_order_request"));assertEquals(5,number("SELECT stock FROM shop_activity"));
 }
 /** 下单已提交但受理结果写失败，重试找回原订单而不是再次扣库存。 */
 @Test void committedOrderSurvivesResultWriteFailure() {
  ShopOrderRequest r=submissions.submit("u",input("a","request_queue_0003"));
  ShopOrderRequestRepository broken=spy(requests);
  doThrow(new IllegalStateException("result failure")).when(broken).finish(anyString(),anyString(),any(),anyString());
  ShopOrderSubmissionService failing=new ShopOrderSubmissionService(broken,events,service,mock(ShopAdmissionGuard.class),new ObjectMapper(),manager,10000);
  String id=event("ORDER_REQUEST",r.getId());
  assertThrows(IllegalStateException.class,()->failing.consume(id));
  assertEquals(1,number("SELECT COUNT(*) FROM shop_order"));
  assertEquals("QUEUED",submissions.result("u",r.getRequestId()).getStatus());
  submissions.consume(id);
  assertEquals("SUCCEEDED",submissions.result("u",r.getRequestId()).getStatus());
  assertEquals(1,number("SELECT COUNT(*) FROM shop_order"));assertEquals(4,number("SELECT stock FROM shop_activity"));
 }
 /** 售罄是可查询业务拒绝，不无限重试；技术故障则保留 QUEUED。 */
 @Test void soldOutBecomesStableRejection() {
  jdbc.update("UPDATE shop_activity SET stock=0");
  ShopOrderRequest r=submissions.submit("u",input("a","request_queue_0004"));
  submissions.consume(event("ORDER_REQUEST",r.getId()));
  assertEquals("REJECTED",submissions.result("u",r.getRequestId()).getStatus());
  assertNotNull(submissions.result("u",r.getRequestId()).getResultMessage());
  assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
 }
 /** 本人查询、请求号语义和积压拒绝均受保护。 */
 @Test void ownershipIntentAndBacklogAreEnforced() {
  ShopOrderRequest r=submissions.submit("u",input("a","request_queue_0005"));
  assertThrows(ShopException.class,()->submissions.result("other",r.getRequestId()));
  assertThrows(ShopException.class,()->submissions.submit("u",input(null,r.getRequestId())));
  ShopOrderSubmissionService bounded=new ShopOrderSubmissionService(requests,events,service,mock(ShopAdmissionGuard.class),new ObjectMapper(),manager,1);
  assertEquals(429,assertThrows(ShopException.class,()->bounded.submit("v",input("a","request_queue_0006"))).getCode());
 }
 /** 二百个请求先入队，两个消费者竞争五件库存：不超卖、无同步创建、结果全部可追踪。 */
 @Test void twoHundredQueuedBuyersCompeteForFiveItems() throws Exception {
  long started=System.nanoTime();
  ExecutorService producers=Executors.newFixedThreadPool(16);
  ExecutorService consumers=Executors.newFixedThreadPool(2);
  try {
   List<Future<ShopOrderRequest>> accepted=new ArrayList<>();
   for(int n=0;n<200;n++) {
    final int i=n;
    accepted.add(producers.submit(()->submissions.submit("buyer"+i,input("a","request_load_"+String.format("%05d",i)))));
   }
   List<ShopOrderRequest> rows=new ArrayList<>();
   for(Future<ShopOrderRequest> f:accepted) rows.add(f.get(30,TimeUnit.SECONDS));
   assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
   List<Future<?>> processed=new ArrayList<>();
   for(ShopOrderRequest r:rows) processed.add(consumers.submit(()->submissions.consume(event("ORDER_REQUEST",r.getId()))));
   for(Future<?> f:processed) f.get(30,TimeUnit.SECONDS);
   assertEquals(5,number("SELECT COUNT(*) FROM shop_order"));
   assertEquals(0,number("SELECT stock FROM shop_activity"));assertEquals(45,number("SELECT stock FROM shop_product"));
   assertEquals(5,number("SELECT COUNT(*) FROM shop_order_request WHERE status='SUCCEEDED'"));
   assertEquals(195,number("SELECT COUNT(*) FROM shop_order_request WHERE status='REJECTED'"));
   System.out.println("QUEUE_CONCURRENCY_REPORT requests=200 producerThreads=16 consumerThreads=2 stock=5 elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
  } finally { producers.shutdownNow();consumers.shutdownNow(); }
 }
 /** 并发提交相同购买意图只保留一个受理和一个消息。 */
 @Test void concurrentSameIntentAcceptsOnce() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(8);
  try {
   List<Future<ShopOrderRequest>> tasks=new ArrayList<>();
   for(int i=0;i<30;i++) tasks.add(pool.submit(()->submissions.submit("u",input("a","request_duplicate_0001"))));
   String id=tasks.get(0).get().getId();
   for(Future<ShopOrderRequest> f:tasks) assertEquals(id,f.get(20,TimeUnit.SECONDS).getId());
   assertEquals(1,number("SELECT COUNT(*) FROM shop_order_request"));
   assertEquals(1,number("SELECT COUNT(*) FROM shop_trade_event"));
  } finally { pool.shutdownNow(); }
 }
 /** 消息必须经消费生成，归属来自订单，重复消费和越权已读均安全。 */
 @Test void orderMessageIsRabbitDrivenAndPrivate() {
  ShopOrder order=service.place("u",input("a","request_message_001"));
  String id=event("ORDER_CREATED",order.getId());
  assertEquals(0,number("SELECT COUNT(*) FROM shop_order_message"));
  trade.consumeEvent(id);trade.consumeEvent(id);
  ShopOrderMessageService messages=new ShopOrderMessageService(events);
  assertEquals(1,messages.list("u",1,10).get("unread"));
  assertEquals(0,messages.list("other",1,10).get("unread"));
  messages.read("other",id);assertEquals(1,events.unread("u"));
  messages.read("u",id);messages.read("u",id);assertEquals(0,events.unread("u"));
  assertEquals(1,number("SELECT COUNT(*) FROM shop_order_message"));
 }
 /** 通知落库后回执失败必须一起回滚，防止重投时重复通知。 */
 @Test void notificationAndReceiptRollbackTogether() {
  ShopOrder order=service.place("u",input("a","request_message_002"));
  String id=event("ORDER_CREATED",order.getId());
  ShopTradeEventRepository broken=spy(events);
  doThrow(new IllegalStateException("receipt failure")).when(broken).receipt(any());
  ShopTradeServiceImpl failing=new ShopTradeServiceImpl(repository,new ShopTradeRepository(jdbc),new MockPaymentGateway("mock",new MockEnvironment()),manager,"admin",broken);
  assertThrows(IllegalStateException.class,()->failing.consumeEvent(id));
  assertEquals(0,number("SELECT COUNT(*) FROM shop_order_message"));
  trade.consumeEvent(id);assertEquals(1,number("SELECT COUNT(*) FROM shop_order_message"));
 }
}
