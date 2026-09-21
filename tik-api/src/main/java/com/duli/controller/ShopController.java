package com.duli.controller;

import com.duli.service.IShopService;
import com.duli.bo.ShopOrderBO;

import com.duli.grace.result.GraceJSONResult;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

/**
 * 商城接口。浏览商品/活动可匿名；订单接口由现有 JwtInterceptor 保护。
 * GET 只读，POST 创建演示订单；金额、库存、身份等可信字段在服务器计算。
 */
@RestController
@RequestMapping("/shop")
public class ShopController {
    private final IShopService shop;
    private final com.duli.service.ShopOrderSubmissionService submissions;
    private final com.duli.service.ShopOrderMessageService messages;
    /** HTTP 下单只负责受理，消息消费负责实际库存事务。 */
    public ShopController(IShopService shop,com.duli.service.ShopOrderSubmissionService submissions,
            com.duli.service.ShopOrderMessageService messages) {
        this.shop=shop;this.submissions=submissions;this.messages=messages;
    }

    @GetMapping("/products")
    public GraceJSONResult products(@RequestParam(defaultValue="1") int page,
                                    @RequestParam(defaultValue="12") int pageSize) {
        return GraceJSONResult.success(shop.products(page, pageSize, false));
    }
    @GetMapping("/seckill")
    public GraceJSONResult seckill(@RequestParam(defaultValue="1") int page,
                                   @RequestParam(defaultValue="12") int pageSize) {
        return GraceJSONResult.success(shop.products(page, pageSize, true));
    }
    @GetMapping("/detail")
    public GraceJSONResult detail(@RequestParam String productId,
                                  @RequestParam(required=false) String activityId) {
        return GraceJSONResult.success(shop.detail(productId, activityId));
    }
    @PostMapping("/order")
    public GraceJSONResult place(@RequestAttribute("currentUserId") String userId,
                                 @Valid @RequestBody ShopOrderBO input) {
        return GraceJSONResult.success(submissions.submit(userId, input));
    }
    @GetMapping("/order/by-request")
    public GraceJSONResult byRequest(@RequestAttribute("currentUserId") String userId,@RequestParam String requestId) {
        return GraceJSONResult.success(shop.orderByRequest(userId,requestId));
    }
    @GetMapping("/orders")
    public GraceJSONResult orders(@RequestAttribute("currentUserId") String userId,
                                  @RequestParam(defaultValue="1") int page,
                                  @RequestParam(defaultValue="12") int pageSize) {
        return GraceJSONResult.success(shop.orders(userId, page, pageSize));
    }
    @GetMapping("/order")
    public GraceJSONResult order(@RequestAttribute("currentUserId") String userId,
                                 @RequestParam String id) {
        return GraceJSONResult.success(shop.order(userId, id));
    }
    /** 按本人原请求号查询排队结果，受理成功不是下单成功。 */
    @GetMapping("/order/result")
    public GraceJSONResult result(@RequestAttribute("currentUserId") String user,@RequestParam String requestId) {
        return GraceJSONResult.success(submissions.result(user,requestId));
    }
    /** 商城通知分页及未读数。 */
    @GetMapping("/messages")
    public GraceJSONResult messages(@RequestAttribute("currentUserId") String user,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {
        return GraceJSONResult.success(messages.list(user,page,pageSize));
    }
    /** 标记本人通知已读。 */
    @PostMapping("/messages/read")
    public GraceJSONResult read(@RequestAttribute("currentUserId") String user,@RequestParam String id) {
        messages.read(user,id);return GraceJSONResult.success();
    }
}
