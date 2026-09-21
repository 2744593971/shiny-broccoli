package com.duli.pojo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 订单保存下单时的商品名称、图片与成交价快照。
 * 商品以后改价/改名不影响历史订单。每单固定一件。
 * 新订单状态：WAIT_PAY -> PAID -> SHIPPED -> COMPLETED；全部支付为本地模拟。
 * WAIT_PAY 可变为 CANCELLED/EXPIRED，PAID 在发货前可变为 REFUNDED。
 * DEMO_CONFIRMED 只用于保留旧版历史单，不会自动升级成已付款。
 */
@Data
public class ShopOrder {
    private String id;
    private String userId;
    private String productId;
    private String activityId;
    private String requestId;
    private String title;
    private String cover;
    private BigDecimal amount;
    private String status;
    private long createdAt;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private Long expiresAt;
    private Long paidAt;
    private Long shippedAt;
    private Long receivedAt;
    private Long refundedAt;
    private String carrier;
    private String trackingNo;
    private String paymentChannel;
}
