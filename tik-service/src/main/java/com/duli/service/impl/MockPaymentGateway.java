package com.duli.service.impl;

import com.duli.service.IPaymentGateway;
import com.duli.pojo.ShopPayment;
import com.duli.bo.ShopPaymentCallbackBO;
import com.duli.exceptions.ShopException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 三种渠道均使用本地 Mock 协议；HMAC 仅用于学习，不是微信/支付宝/银行卡官方验签。 */
@Component
public class MockPaymentGateway implements IPaymentGateway {
    private final String mode;
    private final Environment environment;
    private final String secret;

    /** 生产注入：开发环境默认 Mock，签名密钥可由配置覆盖。 */
    @Autowired
    public MockPaymentGateway(@Value("${shop.payment.mode:mock}") String mode,Environment environment,
            @Value("${shop.payment.mock-secret:local-learning-only-change-me}") String secret) {
        this.mode=mode;this.environment=environment;this.secret=secret;
    }

    /** 测试构造器，使用固定学习密钥方便离线验证。 */
    public MockPaymentGateway(String mode,Environment environment) {
        this(mode,environment,"local-learning-only-change-me");
    }

    /** prod/production 环境始终禁用 Mock，即使误配置为 mock。 */
    public boolean testEnabled() {
        return "mock".equalsIgnoreCase(mode)&&!environment.acceptsProfiles(Profiles.of("prod","production"));
    }

    /** 在任何渠道操作前执行环境限制。 */
    private void guard() {
        if(!testEnabled()) throw new ShopException(503,"当前环境禁止模拟支付，真实渠道未接入");
    }

    /** 校验统一渠道枚举，防止任意字符串成为支付渠道。 */
    private void channel(String value) {
        if(!Arrays.asList("WECHAT","ALIPAY","BANK_CARD").contains(value))
            throw new ShopException(400,"不支持的支付渠道");
    }

    /** 返回明确标注模拟的统一收银台参数。 */
    public Map<String,Object> prepare(ShopPayment payment) {
        guard();channel(payment.getChannel());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("testOnly",true);result.put("mode","MOCK");result.put("currency","CNY");
        result.put("channel",payment.getChannel());
        result.put("notice","本地模拟支付，不调用微信、支付宝或银行，不扣真实费用");
        return result;
    }

    /** 相同支付单始终返回相同交易号，便于回调重放和幂等校验。 */
    public String confirmTest(ShopPayment payment) {
        guard();channel(payment.getChannel());return "mock_"+payment.getId();
    }

    /** 全额模拟退款使用稳定渠道退款号。 */
    public String refundTest(ShopPayment payment) {
        guard();channel(payment.getChannel());return "mock_refund_"+payment.getId();
    }

    /** Mock 无远程未决交易；方法保留统一关单边界。 */
    public void close(ShopPayment payment) {
        if(payment!=null) { channel(payment.getChannel()); }
        // 不发生外部资金操作；停用模拟支付后仍允许关闭本地未支付单。
    }

    /** 生成确定性的成功通知，旧测试确认入口也必须经过统一回调逻辑。 */
    public ShopPaymentCallbackBO notification(ShopPayment payment,String tradeId) {
        guard();channel(payment.getChannel());
        ShopPaymentCallbackBO callback=new ShopPaymentCallbackBO();
        callback.setEventId("pay_"+payment.getId());callback.setPaymentId(payment.getId());
        callback.setOrderId(payment.getOrderId());callback.setChannel(payment.getChannel());
        callback.setAmount(payment.getAmount());callback.setCurrency("CNY");
        callback.setStatus("SUCCEEDED");callback.setTradeId(tradeId);
        callback.setSignature(sign(callback));return callback;
    }

    /** 验签后仍须由业务层校验支付单归属、金额、渠道和订单状态。 */
    public String verify(ShopPaymentCallbackBO c) {
        guard();
        if(c==null||!valid(c.getEventId(),64)||!valid(c.getPaymentId(),32)||!valid(c.getOrderId(),32)
                ||!valid(c.getTradeId(),100)||c.getAmount()==null||c.getAmount().signum()<0
                ||c.getAmount().scale()>2||c.getAmount().precision()-c.getAmount().scale()>10
                ||!"CNY".equals(c.getCurrency())||!"SUCCEEDED".equals(c.getStatus()))
            throw new ShopException(400,"回调字段不合法");
        channel(c.getChannel());
        String expected=sign(c);
        if(c.getSignature()==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                c.getSignature().getBytes(StandardCharsets.US_ASCII)))
            throw new ShopException(403,"Mock 回调签名错误");
        return expected;
    }

    /** 限制签名字段字符集，防止分隔符歧义。 */
    private boolean valid(String value,int length) {
        return value!=null&&value.matches("[A-Za-z0-9_-]{1,"+length+"}");
    }

    /** 对所有影响交易结果的字段签名，金额规范化为两位小数。 */
    private String sign(ShopPaymentCallbackBO c) {
        String body=String.join("|",c.getEventId(),c.getPaymentId(),c.getOrderId(),c.getChannel(),
                c.getAmount().setScale(2).toPlainString(),c.getCurrency(),c.getStatus(),c.getTradeId());
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            StringBuilder hex=new StringBuilder();
            for(byte b:mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x",b&255));
            return hex.toString();
        } catch(java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Mock signature unavailable",error);
        }
    }
}
