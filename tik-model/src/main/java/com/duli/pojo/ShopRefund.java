package com.duli.pojo;

import lombok.Data;
import java.math.BigDecimal;

/** 一笔支付只允许一笔全额 Mock 退款；唯一约束保证重试复用原退款号。 */
@Data
public class ShopRefund {
    private String id;
    private String paymentId;
    private String orderId;
    private BigDecimal amount;
    private String status;
    private String externalRefundId;
}
