package com.duli.service;

import com.duli.bo.ShopOrderBO;
import com.duli.pojo.*;
import com.duli.mapper.*;
import com.duli.exceptions.ShopException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** HTTP 只受理，RabbitMQ 执行下单；请求和订单分别持久化，崩溃重试靠业务幂等衔接。 */
@Service
public class ShopOrderSubmissionService {
 private final ShopOrderRequestRepository requests;
 private final ShopTradeEventRepository events;
 private final IShopService shop;
 private final ShopAdmissionGuard guard;
 private final ObjectMapper json;
 private final TransactionTemplate tx,orderTx;
 private final int maxPending;
 /** 外层锁请求，内层新事务下单；每个消费线程最多占两个数据库连接。 */
 public ShopOrderSubmissionService(ShopOrderRequestRepository requests,ShopTradeEventRepository events,IShopService shop,
     ShopAdmissionGuard guard,ObjectMapper json,PlatformTransactionManager manager,@Value("${shop.admission.max-pending:10000}") int maxPending) {
  this.requests=requests;this.events=events;this.shop=shop;this.guard=guard;this.json=json;this.maxPending=Math.max(1,maxPending);
  this.tx=new TransactionTemplate(manager);this.tx.setTimeout(15);
  this.orderTx=new TransactionTemplate(manager);this.orderTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  this.orderTx.setTimeout(5);
 }
 /** 受理成功不表示买到；用户通过原请求号读取最终订单或拒绝原因。 */
 public ShopOrderRequest submit(String user,ShopOrderBO body) {
  guard.enter(user);
  try {
   ShopOrderRequest existing=requests.find(user,body.getRequestId());
   if(existing!=null) return same(existing,body);
   if(requests.pending()>=maxPending) throw new ShopException(429,"排队人数已满，请稍后重试");
   String payload;
   try { payload=json.writeValueAsString(body); }
   catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new ShopException(400,"下单参数无法序列化"); }
   try {
    return tx.execute(status->{
     String id=UUID.randomUUID().toString().replace("-","");
     requests.insert(id,user,body,payload);
     events.enqueue("ORDER_REQUEST",id,events.now());
     return requests.find(user,body.getRequestId());
    });
   } catch(DuplicateKeyException race) {
    ShopOrderRequest winner=requests.find(user,body.getRequestId());
    if(winner==null) throw race;
    return same(winner,body);
   }
  } finally { guard.leave(); }
 }
 /** 同一幂等键只代表最初的购买意图，不能被换商品或场次复用。 */
 private ShopOrderRequest same(ShopOrderRequest r,ShopOrderBO body) {
  if(!Objects.equals(r.getProductId(),body.getProductId())||!Objects.equals(r.getActivityId(),body.getActivityId()))
   throw new ShopException(409,"请求编号已用于其他商品");
  return r;
 }
 /** 只查询本人受理结果；503/网络超时后必须沿用原 requestId。 */
 public ShopOrderRequest result(String user,String request) {
  if(user==null||user.trim().isEmpty()) throw new ShopException(401,"请先登录");
  ShopOrderRequest r=requests.find(user,request);
  if(r==null) throw new ShopException(404,"尚未受理该请求");
  return r;
 }
 /** 限量消费者执行库存事务；订单已提交但结果写入失败时重试找回原订单。 */
 public void consume(String eventId) {
  tx.execute(status->{
   ShopTradeEvent event=events.find(eventId);
   if(event==null||!"ORDER_REQUEST".equals(event.getEventType())) throw new ShopException(400,"不是有效的下单事件");
   ShopOrderRequest r=requests.lock(event.getOrderId());
   if(r==null) throw new ShopException(404,"下单请求不存在");
   if(events.consumed(eventId)) return null;
   if("QUEUED".equals(r.getStatus())) {
    ShopOrderBO body;
    try { body=json.readValue(r.getPayload(),ShopOrderBO.class); }
    catch(java.io.IOException error) { throw new IllegalStateException("Invalid persisted order request",error); }
    try {
     ShopOrder order=orderTx.execute(inner->shop.place(r.getUserId(),body));
     requests.finish(r.getId(),"SUCCEEDED",order.getId(),"下单成功，请在十五分钟内支付");
    } catch(ShopException rejection) {
     if(!Arrays.asList(400,404,409).contains(rejection.getCode())) throw rejection;
     requests.finish(r.getId(),"REJECTED",null,rejection.getMessage());
    }
   }
   events.receipt(event);return null;
  });
 }
}
