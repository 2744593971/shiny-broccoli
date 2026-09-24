package com.duli.mapper;
import com.duli.pojo.ShopOrderRequest;
import com.duli.bo.ShopOrderBO;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.util.*;

/** 请求按用户和幂等键唯一；受理事务只写请求及 Outbox，不竞争库存。 */
@Repository
public class ShopOrderRequestRepository {
 private final JdbcTemplate jdbc;
 private static final RowMapper<ShopOrderRequest> ROW=(rs,n)->{
  ShopOrderRequest r=new ShopOrderRequest();r.setId(rs.getString("id"));r.setUserId(rs.getString("user_id"));
  r.setRequestId(rs.getString("request_id"));r.setProductId(rs.getString("product_id"));r.setActivityId(rs.getString("activity_id"));
  r.setPayload(rs.getString("payload"));r.setStatus(rs.getString("status"));r.setOrderId(rs.getString("order_id"));
  r.setResultMessage(rs.getString("result_message"));r.setCreatedAt(rs.getTimestamp("created_at").getTime());return r;
 };
 /** 注入与订单相同的数据源。 */
 public ShopOrderRequestRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 /** 按本人幂等键查询排队结果。 */
 public ShopOrderRequest find(String user,String request) {
  List<ShopOrderRequest> rows=jdbc.query("SELECT * FROM shop_order_request WHERE user_id=? AND request_id=?",ROW,user,request);
  return rows.isEmpty()?null:rows.get(0);
 }
 /** 消费时锁请求行，同一受理请求不会被两个消费者同时执行。 */
 // FOR UPDATE 与消费外层事务配合，两个消费者即使收到同一事件，也只能串行处理同一请求。
 public ShopOrderRequest lock(String id) {
  List<ShopOrderRequest> rows=jdbc.query("SELECT * FROM shop_order_request WHERE id=? FOR UPDATE",ROW,id);
  return rows.isEmpty()?null:rows.get(0);
 }
 /** 保存受理正文；结果完成时清理正文，减少重复保存收货信息。 */
 public void insert(String id,String user,ShopOrderBO body,String payload) {
  jdbc.update("INSERT INTO shop_order_request(id,user_id,request_id,product_id,activity_id,payload) VALUES(?,?,?,?,?,?)",
      id,user,body.getRequestId(),body.getProductId(),body.getActivityId(),payload);
 }
 /** 记录确定结果并清理请求正文，技术故障不能误写成业务失败。 */
 // 只允许 QUEUED 转终态，并清空包含收货信息的 payload；终态重复消费不会改写结果。
 public void finish(String id,String status,String orderId,String message) {
  jdbc.update("UPDATE shop_order_request SET status=?,order_id=?,result_message=?,payload=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'",
      status,orderId,message,id);
 }
 /** 积压超过软上限时暂停受理；并发窗口可能略超，非精确队列配额。 */
 public int pending() {
  return jdbc.queryForObject("SELECT COUNT(*) FROM shop_order_request WHERE status='QUEUED'",Integer.class);
 }
}
