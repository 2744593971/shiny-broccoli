package com.duli.mapper;

import com.duli.pojo.ShopTradeEvent;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.util.*;

/** 交易专用 Outbox；与社交通知表隔离，订单事务与事件插入共用数据源。 */
@Repository
public class ShopTradeEventRepository {
    private final JdbcTemplate jdbc;
    private static final String DUE="((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR "
            +"(status='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP))";
    private static final RowMapper<ShopTradeEvent> ROW=(rs,n)->{
        ShopTradeEvent e=new ShopTradeEvent();
        e.setId(rs.getString("id"));e.setOrderId(rs.getString("order_id"));
        e.setEventType(rs.getString("event_type"));e.setStatus(rs.getString("status"));
        e.setAttempts(rs.getInt("attempts"));e.setLeaseToken(rs.getString("lease_token"));
        Timestamp consumed=rs.getTimestamp("consumed_at");
        e.setConsumedAt(consumed==null?null:consumed.getTime());return e;
    };

    /** 使用与订单相同的数据源构造访问层。 */
    public ShopTradeEventRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 以数据库时钟安排到期事件，避免各实例时钟漂移。 */
    public long now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).getTime(); }

    /** 在业务事务内记录事件；每个订单的每种状态事件只生成一次。 */
    public void enqueue(String type,String orderId,long dueAt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Trade event requires a transaction");
        if(!Arrays.asList("ORDER_EXPIRE","PAYMENT_SUCCEEDED","REFUND_SUCCEEDED","ORDER_CLOSED","ORDER_CREATED","ORDER_SHIPPED","ORDER_COMPLETED","ORDER_REQUEST").contains(type))
            throw new IllegalArgumentException("Unknown trade event");
        jdbc.update("INSERT INTO shop_trade_event(id,event_type,order_id,next_attempt_at) VALUES(?,?,?,?)",
                UUID.randomUUID().toString().replace("-",""),type,orderId,new Timestamp(dueAt));
    }

    /** 返回待投递或租约过期的事件；数据库截止时间实现持久化延迟。 */
    public List<String> dueIds() {
        return jdbc.queryForList("SELECT id FROM shop_trade_event WHERE "+DUE
                +" AND attempts<10 AND consumed_at IS NULL ORDER BY next_attempt_at,id LIMIT 100",String.class);
    }

    /** 原子抢占一分钟租约，避免多实例同时发送同一事件。 */
    public ShopTradeEvent claim(String id,String token) {
        if(jdbc.update("UPDATE shop_trade_event SET status='IN_FLIGHT',attempts=attempts+1,lease_token=?,lease_until=? "
                +"WHERE id=? AND "+DUE+" AND attempts<10 AND consumed_at IS NULL",
                token,new Timestamp(now()+60000),id)!=1) return null;
        List<ShopTradeEvent> rows=jdbc.query("SELECT * FROM shop_trade_event WHERE id=? AND lease_token=?",ROW,id,token);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** 查询权威事件，不信任 MQ 中附带的业务状态。 */
    public ShopTradeEvent find(String id) {
        List<ShopTradeEvent> rows=jdbc.query("SELECT * FROM shop_trade_event WHERE id=?",ROW,id);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** ACK 且无 Return 后确认发送；过期工作者不能覆盖新租约。 */
    public void sent(ShopTradeEvent e) {
        jdbc.update("UPDATE shop_trade_event SET status='SENT',published_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,last_error=NULL "
                +"WHERE id=? AND status='IN_FLIGHT' AND lease_token=?",e.getId(),e.getLeaseToken());
    }

    /** 有限退避重试；第十次仍失败时保留 FAILED 记录供重放。 */
    public void failed(ShopTradeEvent e,String error) {
        long delay=Math.min(600,5L*(1L<<Math.min(e.getAttempts()-1,7)))*1000;
        jdbc.update("UPDATE shop_trade_event SET status=?,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL "
                +"WHERE id=? AND status='IN_FLIGHT' AND lease_token=?",
                e.getAttempts()>=10?"FAILED":"PENDING",new Timestamp(now()+delay),error,e.getId(),e.getLeaseToken());
    }

    /** 回收最后一次崩溃的租约；已消费的事件无需再次发送。 */
    public void expireLeases() {
        // 已确认发送但十分钟没有消费回执时转为可观察失败，覆盖消费者无法写失败标记的场景。
        jdbc.update("UPDATE shop_trade_event SET status='FAILED',last_error='CONSUMPTION_TIMEOUT' "
                +"WHERE status='SENT' AND consumed_at IS NULL AND published_at<=?",new Timestamp(now()-600000));
        jdbc.update("UPDATE shop_trade_event SET status='FAILED',lease_token=NULL,lease_until=NULL,last_error='LEASE_EXHAUSTED' "
                +"WHERE "+DUE+" AND attempts>=10 AND consumed_at IS NULL");
        jdbc.update("UPDATE shop_trade_event SET status='SENT',lease_token=NULL,lease_until=NULL "
                +"WHERE consumed_at IS NOT NULL AND status<>'SENT'");
    }

    /** 在订单行锁保护下检查消费回执，重复投递直接成功返回。 */
    public boolean consumed(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shop_trade_receipt WHERE event_id=?",Integer.class,id)>0;
    }

    /** 与消费者业务一同提交；失败回滚时回执也消失，允许安全重试。 */
    public void receipt(ShopTradeEvent e) {
        jdbc.update("INSERT INTO shop_trade_receipt(event_id,order_id,event_type) VALUES(?,?,?)",
                e.getId(),e.getOrderId(),e.getEventType());
        jdbc.update("UPDATE shop_trade_event SET consumed_at=CURRENT_TIMESTAMP WHERE id=?",e.getId());
    }

    /** 消费重试耗尽时记录失败原因，独立调用并保留原事件。 */
    public void dead(String id) {
        jdbc.update("UPDATE shop_trade_event SET status='FAILED',last_error='CONSUMER_EXHAUSTED',lease_token=NULL,lease_until=NULL "
                +"WHERE id=? AND consumed_at IS NULL",id);
    }

    /** 管理员按单条事件重放；已消费事件不能再次执行业务。 */
    public boolean replay(String id) {
        return jdbc.update("UPDATE shop_trade_event SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,"
                +"last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=? AND status='FAILED' AND consumed_at IS NULL",id)==1;
    }

    /** 查询可恢复的失败事件，避免输出收货地址及支付正文。 */
    public List<ShopTradeEvent> failures() {
        return jdbc.query("SELECT * FROM shop_trade_event WHERE status='FAILED' AND consumed_at IS NULL ORDER BY created_at LIMIT 100",ROW);
    }

    /** 在 MQ 消费事务内生成本人订单通知；事件 ID 主键与消费回执共同防重复。 */
    public void notifyOrder(ShopTradeEvent event,com.duli.pojo.ShopOrder order) {
        String content;
        switch(event.getEventType()) {
            case "ORDER_CREATED": content="下单成功，请在支付截止时间前付款";break;
            case "PAYMENT_SUCCEEDED": content="支付成功，正在等待发货";break;
            case "ORDER_SHIPPED": content="订单已发货，可查看物流信息";break;
            case "ORDER_COMPLETED": content="订单已确认收货，交易完成";break;
            case "REFUND_SUCCEEDED": content="模拟退款成功，订单已退款";break;
            case "ORDER_CLOSED": content="EXPIRED".equals(order.getStatus())?"订单支付超时，已自动关闭":"订单已取消";break;
            default: return;
        }
        jdbc.update("INSERT INTO shop_order_message(id,user_id,order_id,event_type,content) VALUES(?,?,?,?,?)",
                event.getId(),order.getUserId(),order.getId(),event.getEventType(),content);
    }

    /** 仅返回当前用户的消息，按消费落库时间分页；不包含收货信息。 */
    public List<com.duli.pojo.ShopOrderMessage> messages(String user,int offset,int size) {
        return jdbc.query("SELECT * FROM shop_order_message WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->{
            com.duli.pojo.ShopOrderMessage m=new com.duli.pojo.ShopOrderMessage();
            m.setId(rs.getString("id"));m.setOrderId(rs.getString("order_id"));m.setEventType(rs.getString("event_type"));
            m.setContent(rs.getString("content"));m.setRead(rs.getTimestamp("read_at")!=null);
            m.setCreatedAt(rs.getTimestamp("created_at").getTime());return m;
        },user,size,offset);
    }

    /** 查询本人未读数，使用用户与已读字段联合索引。 */
    public int unread(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shop_order_message WHERE user_id=? AND read_at IS NULL",Integer.class,user);
    }

    /** 标记单条消息已读，WHERE 中同时校验归属，重复请求幂等。 */
    public void readMessage(String user,String id) {
        jdbc.update("UPDATE shop_order_message SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=? AND read_at IS NULL",user,id);
    }
}
