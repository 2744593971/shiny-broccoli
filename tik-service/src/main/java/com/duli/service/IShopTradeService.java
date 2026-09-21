package com.duli.service;
import com.duli.pojo.ShopOrder;
import com.duli.bo.*;
import java.util.Map;
/** 订单交易状态流转接口；userId 一律由已验证 JWT 提供。 */
public interface IShopTradeService {
    /** 获取模拟渠道、开关和管理员权限。 */
    Map<String,Object> config(String userId);
    /** 创建或复用服务端支付单。 */
    Map<String,Object> payment(String userId,ShopPaymentBO input);
    /** 生成 Mock 回调并走统一支付状态机。 */
    ShopOrder confirmTest(String userId,String orderId);
    /** 取消未支付订单并幂等回补库存。 */
    ShopOrder cancel(String userId,String orderId);
    /** 完成未发货订单的全额模拟退款。 */
    ShopOrder refund(String userId,String orderId);
    /** 买家确认收货。 */
    ShopOrder receive(String userId,String orderId);
    /** 分页查询管理员可管理的订单。 */
    Map<String,Object> adminOrders(String userId,int page,int size);
    /** 管理员登记物流并发货。 */
    ShopOrder ship(String userId,ShopShipmentBO input);
    /** 数据库扫描兜底关闭超时未支付订单。 */
    void expireOrders();
    /** 查询本人支付、退款和回调记录。 */
    Map<String,Object> paymentDetail(String userId,String orderId);
    /** 生成学习用签名 Mock 通知，不改变支付状态。 */
    ShopPaymentCallbackBO mockNotification(String userId,String orderId);
    /** 处理已登录用户所属订单的 Mock 回调。 */
    ShopOrder callback(String userId,ShopPaymentCallbackBO callback);
    /** 按事件 ID 幂等消费 RabbitMQ 交易事件。 */
    void consumeEvent(String eventId);
    /** 查询失败事件，仅管理员允许操作。 */
    java.util.List<com.duli.pojo.ShopTradeEvent> failedEvents(String userId);
    /** 重放一条失败且未消费的事件。 */
    boolean replayEvent(String userId,String id);
}
