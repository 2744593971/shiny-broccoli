package com.duli.pojo;
import lombok.Data;
/** 商城独立订单通知，不混入社交通知，也不返回收货信息。 */
@Data
public class ShopOrderMessage {
 private String id;
 private String orderId;
 private String eventType;
 private String content;
 private boolean read;
 private long createdAt;
}
