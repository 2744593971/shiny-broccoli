package com.duli.service;

import com.duli.pojo.ShopPayment;
import com.duli.bo.ShopPaymentCallbackBO;
import java.util.Map;

/** 微信、支付宝、银行卡统一适配边界；当前唯一实现是无网络、无资金变动的 Mock。 */
public interface IPaymentGateway {
    /** 判断当前环境是否允许模拟支付。 */
    boolean testEnabled();
    /** 根据服务端支付单准备模拟收银参数。 */
    Map<String,Object> prepare(ShopPayment payment);
    /** 生成幂等模拟交易号，不直接改变本地订单状态。 */
    String confirmTest(ShopPayment payment);
    /** 生成幂等模拟退款号，不调用真实渠道。 */
    String refundTest(ShopPayment payment);
    /** 模拟关闭渠道交易；真实适配器应通过渠道查单/关单确认结果。 */
    void close(ShopPayment payment);
    /** 构造带签名的学习用回调，供测试确认和回调演练复用。 */
    ShopPaymentCallbackBO notification(ShopPayment payment,String tradeId);
    /** 校验 Mock 回调签名及协议字段，返回可持久化的消息指纹。 */
    String verify(ShopPaymentCallbackBO callback);
}
