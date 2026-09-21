package com.duli.bo;
import lombok.Data;
import javax.validation.constraints.*;
/** 创建测试支付只提交订单与渠道，不提交银行卡号、CVV、密码和金额。 */
@Data
public class ShopPaymentBO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") private String orderId;
    @NotBlank @Pattern(regexp="WECHAT|ALIPAY|BANK_CARD") private String channel;
}
