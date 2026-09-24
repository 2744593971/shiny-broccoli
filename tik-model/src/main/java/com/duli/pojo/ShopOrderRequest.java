package com.duli.pojo;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
/** 异步下单受理结果；查询响应不会返回收货正文和用户 ID。 */
@Data
public class ShopOrderRequest {
 private String id;
 @JsonIgnore private String userId;
 private String requestId;
 private String productId;
 private String activityId;
 @JsonIgnore private String payload;
 // QUEUED=已受理未决；SUCCEEDED=真实订单已生成；REJECTED=确定的业务拒绝。
 private String status;
 // 只有 SUCCEEDED 的 orderId 才是可进入订单详情的真实订单编号。
 private String orderId;
 private String resultMessage;
 private long createdAt;
}
