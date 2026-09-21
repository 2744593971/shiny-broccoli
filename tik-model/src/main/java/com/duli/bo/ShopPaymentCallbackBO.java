package com.duli.bo;

import lombok.Data;
import java.math.BigDecimal;
import javax.validation.constraints.*;

/** 学习用 Mock 回调，不代表任何官方支付协议；金额为元，币种固定 CNY。 */
@Data
public class ShopPaymentCallbackBO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}")
    private String eventId;
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}")
    private String paymentId;
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}")
    private String orderId;
    @NotBlank @Pattern(regexp="WECHAT|ALIPAY|BANK_CARD")
    private String channel;
    @NotNull @DecimalMin("0.00") @Digits(integer=10,fraction=2)
    private BigDecimal amount;
    @NotBlank @Pattern(regexp="CNY")
    private String currency;
    @NotBlank @Pattern(regexp="SUCCEEDED")
    private String status;
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}")
    private String tradeId;
    @NotBlank @Pattern(regexp="[a-f0-9]{64}")
    private String signature;
}
