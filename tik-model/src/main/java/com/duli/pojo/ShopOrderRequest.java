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
 private String status;
 private String orderId;
 private String resultMessage;
 private long createdAt;
}
