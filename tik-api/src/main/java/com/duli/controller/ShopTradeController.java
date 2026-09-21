package com.duli.controller;
import com.duli.bo.*;
import com.duli.service.IShopTradeService;
import com.duli.grace.result.GraceJSONResult;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

/** 交易接口全部需要 JWT，管理员权限由 service 再校验；没有公开的“直接标记已付款”接口。 */
@RestController
@RequestMapping("/shop")
public class ShopTradeController {
    private final IShopTradeService trade;
    /** 注入交易业务接口，控制器不直接访问数据库。 */
    public ShopTradeController(IShopTradeService trade) { this.trade=trade; }
    /** 查询学习支付配置与管理员权限。 */
    @GetMapping("/trade/config")
    public GraceJSONResult config(@RequestAttribute("currentUserId") String user) {
        return GraceJSONResult.success(trade.config(user));
    }
    /** 创建或复用本人订单的模拟支付单。 */
    @PostMapping("/payment")
    public GraceJSONResult payment(@RequestAttribute("currentUserId") String user,@Valid @RequestBody ShopPaymentBO body) {
        return GraceJSONResult.success(trade.payment(user,body));
    }
    /** 兼容原前端确认入口，实际调用统一 Mock 回调处理。 */
    @PostMapping("/payment/test-confirm")
    public GraceJSONResult confirm(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.confirmTest(user,orderId));
    }
    /** 取消本人未支付订单。 */
    @PostMapping("/order/cancel")
    public GraceJSONResult cancel(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.cancel(user,orderId));
    }
    /** 申请本人未发货订单的全额 Mock 退款。 */
    @PostMapping("/order/refund")
    public GraceJSONResult refund(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.refund(user,orderId));
    }
    /** 确认本人订单已收货。 */
    @PostMapping("/order/receive")
    public GraceJSONResult receive(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.receive(user,orderId));
    }
    /** 查询管理员订单列表，服务层校验权限。 */
    @GetMapping("/admin/orders")
    public GraceJSONResult adminOrders(@RequestAttribute("currentUserId") String user,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize) {
        return GraceJSONResult.success(trade.adminOrders(user,page,pageSize));
    }
    /** 校验物流参数并提交管理员发货操作。 */
    @PostMapping("/admin/ship")
    public GraceJSONResult ship(@RequestAttribute("currentUserId") String user,@Valid @RequestBody ShopShipmentBO body) {
        return GraceJSONResult.success(trade.ship(user,body));
    }
    /** 本人支付详情，含退款单和回调结果，不能跨账号查询。 */
    @GetMapping("/payment/detail")
    public GraceJSONResult paymentDetail(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.paymentDetail(user,orderId));
    }
    /** 获取签名 Mock 回调体用于学习重放，仅在模拟环境且订单属于本人时返回。 */
    @PostMapping("/payment/mock-notification")
    public GraceJSONResult notification(@RequestAttribute("currentUserId") String user,@RequestParam String orderId) {
        return GraceJSONResult.success(trade.mockNotification(user,orderId));
    }
    /** 学习用回调保留 JWT 保护，同时执行签名校验；不是对外开放的真实支付回调。 */
    @PostMapping("/payment/mock-callback")
    public GraceJSONResult callback(@RequestAttribute("currentUserId") String user,@Valid @RequestBody ShopPaymentCallbackBO body) {
        return GraceJSONResult.success(trade.callback(user,body));
    }
    /** 查询交易消息失败记录，由业务层再次校验管理员身份。 */
    @GetMapping("/admin/payment-events")
    public GraceJSONResult failedEvents(@RequestAttribute("currentUserId") String user) {
        return GraceJSONResult.success(trade.failedEvents(user));
    }
    /** 单条重放失败消息，成功消费过的消息不再重放。 */
    @PostMapping("/admin/payment-events/replay")
    public GraceJSONResult replay(@RequestAttribute("currentUserId") String user,@RequestParam String eventId) {
        return GraceJSONResult.success(trade.replayEvent(user,eventId));
    }
}
