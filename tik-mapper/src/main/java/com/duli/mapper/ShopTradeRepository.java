package com.duli.mapper;
import com.duli.pojo.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * 交易 SQL 层。状态流转前必须锁订单行，所有支付/退款/发货操作统一先锁订单。
 * 不直接从 Controller 调用本类，事务边界由 ShopTradeServiceImpl 维护。
 */
@Repository
public class ShopTradeRepository {
    private final JdbcTemplate jdbc;
    /** 注入订单事务共用的 JdbcTemplate。 */
    public ShopTradeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 按订单及可选归属加行锁，内部任务可不传用户 ID。 */
    public ShopOrder lock(String id,String userId) {
        List<ShopOrder> rows=userId==null
            ?jdbc.query("SELECT * FROM shop_order WHERE id=? FOR UPDATE",ShopRepository.ORDER,id)
            :jdbc.query("SELECT * FROM shop_order WHERE id=? AND user_id=? FOR UPDATE",ShopRepository.ORDER,id,userId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static final RowMapper<ShopPayment> PAYMENT=(rs,n)->{
        ShopPayment p=new ShopPayment(); p.setId(rs.getString("id"));p.setOrderId(rs.getString("order_id"));
        p.setChannel(rs.getString("channel"));p.setAmount(rs.getBigDecimal("amount"));p.setStatus(rs.getString("status"));
        p.setExternalTradeId(rs.getString("external_trade_id"));p.setRefundId(rs.getString("refund_id"));return p;
    };
    /** 按订单查询唯一支付单。 */
    public ShopPayment payment(String orderId) {
        List<ShopPayment> rows=jdbc.query("SELECT * FROM shop_payment WHERE order_id=?",PAYMENT,orderId);
        return rows.isEmpty()?null:rows.get(0);
    }
    /** 插入待支付单，订单唯一约束保证支付准备幂等。 */
    public void insertPayment(ShopPayment p) {
        jdbc.update("INSERT INTO shop_payment(id,order_id,channel,amount,status) VALUES(?,?,?,?,'PENDING')",
            p.getId(),p.getOrderId(),p.getChannel(),p.getAmount());
    }
    /** 只允许待支付的 Mock 支付单切换渠道。 */
    public void changeChannel(String id,String channel) {
        jdbc.update("UPDATE shop_payment SET channel=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",channel,id);
    }
    /** 在调用方订单锁与事务内同时标记支付成功和订单已支付。 */
    public void paid(ShopOrder order,ShopPayment p,String externalId) {
        jdbc.update("UPDATE shop_payment SET status='SUCCEEDED',external_trade_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            externalId,p.getId());
        jdbc.update("UPDATE shop_order SET status='PAID',paid_at=CURRENT_TIMESTAMP,payment_channel=? WHERE id=?",p.getChannel(),order.getId());
    }
    /** 状态流转与回补库存必须在同一个事务中，只在第一次关闭/退款时执行。 */
    public void restoreStock(ShopOrder order) {
        if(order.getActivityId()!=null)
            jdbc.update("UPDATE shop_activity SET stock=stock+1 WHERE id=?",order.getActivityId());
        jdbc.update("UPDATE shop_product SET stock=stock+1 WHERE id=?",order.getProductId());
    }
    /** 关闭订单及待支付单，并在同一事务回补库存。 */
    public void close(ShopOrder order,String status) {
        jdbc.update("UPDATE shop_order SET status=? WHERE id=?",status,order.getId());
        jdbc.update("UPDATE shop_payment SET status='CLOSED',updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND status='PENDING'",order.getId());
        restoreStock(order);
    }
    /** 写入模拟退款结果并回补库存，调用前必须校验订单已支付未发货。 */
    public void refund(ShopOrder order,ShopPayment payment,String refundId) {
        jdbc.update("UPDATE shop_payment SET status='REFUNDED',refund_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",refundId,payment.getId());
        jdbc.update("UPDATE shop_order SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP WHERE id=?",order.getId());
        restoreStock(order);
    }
    /** 保存管理员登记的物流信息并推进到已发货。 */
    public void ship(ShopOrder order,String carrier,String trackingNo) {
        jdbc.update("UPDATE shop_order SET status='SHIPPED',carrier=?,tracking_no=?,shipped_at=CURRENT_TIMESTAMP WHERE id=?",
            carrier,trackingNo,order.getId());
    }
    /** 写入买家收货时间并完成订单。 */
    public void receive(ShopOrder order) {
        jdbc.update("UPDATE shop_order SET status='COMPLETED',received_at=CURRENT_TIMESTAMP WHERE id=?",order.getId());
    }
    /** 每批最多取一百张超时未支付订单。 */
    public List<String> expiredIds() {
        return jdbc.queryForList("SELECT id FROM shop_order WHERE status='WAIT_PAY' AND expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 100",String.class);
    }
    /** 按时间分页查询管理员可见交易订单。 */
    public List<ShopOrder> adminOrders(int offset,int limit) {
        return jdbc.query("SELECT * FROM shop_order WHERE status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            ShopRepository.ORDER,limit,offset);
    }

    /** 查询已处理回调的指纹；同一事件号不能承载不同内容。 */
    public String callbackFingerprint(String eventId) {
        List<String> rows=jdbc.queryForList("SELECT fingerprint FROM shop_payment_callback WHERE event_id=?",String.class,eventId);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** 与支付状态更新同事务保存通知结果，失败时允许渠道重试。 */
    public void callback(String eventId,String paymentId,String fingerprint,String outcome) {
        jdbc.update("INSERT INTO shop_payment_callback(event_id,payment_id,fingerprint,outcome) VALUES(?,?,?,?)",
                eventId,paymentId,fingerprint,outcome);
    }

    /** 返回支付通知处理记录，查询调用方必须已验证订单归属。 */
    public List<Map<String,Object>> callbacks(String paymentId) {
        return jdbc.queryForList("SELECT event_id,outcome,created_at FROM shop_payment_callback WHERE payment_id=? ORDER BY created_at",paymentId);
    }

    /** 查询唯一的全额退款单，重复退款复用已完成结果。 */
    public ShopRefund refundRecord(String orderId) {
        List<ShopRefund> rows=jdbc.query("SELECT * FROM shop_refund WHERE order_id=?",(rs,n)->{
            ShopRefund r=new ShopRefund();r.setId(rs.getString("id"));r.setOrderId(rs.getString("order_id"));
            r.setPaymentId(rs.getString("payment_id"));r.setAmount(rs.getBigDecimal("amount"));
            r.setStatus(rs.getString("status"));r.setExternalRefundId(rs.getString("external_refund_id"));return r;
        },orderId);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** 创建全额退款申请；一笔支付一个退款单的唯一约束防止重复退款。 */
    public ShopRefund requestRefund(ShopPayment payment) {
        ShopRefund refund=new ShopRefund();refund.setId(UUID.randomUUID().toString().replace("-",""));
        refund.setPaymentId(payment.getId());refund.setOrderId(payment.getOrderId());
        refund.setAmount(payment.getAmount());refund.setStatus("REQUESTED");
        jdbc.update("INSERT INTO shop_refund(id,payment_id,order_id,amount,status) VALUES(?,?,?,?,'REQUESTED')",
                refund.getId(),refund.getPaymentId(),refund.getOrderId(),refund.getAmount());
        return refund;
    }

    /** 记录 Mock 退款成功；与支付、订单及库存更新一同提交。 */
    public void refundSucceeded(String id,String externalId) {
        jdbc.update("UPDATE shop_refund SET status='SUCCEEDED',external_refund_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='REQUESTED'",
                externalId,id);
    }
}
