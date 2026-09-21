package com.duli.pojo;
import lombok.Data;
import java.math.BigDecimal;
/** 支付记录。价格来源于订单快照，绝不使用前端提交的金额。 */
@Data
public class ShopPayment {
    private String id;
    private String orderId;
    private String channel;
    private BigDecimal amount;
    private String status;
    private String externalTradeId;
    private String refundId;
}
