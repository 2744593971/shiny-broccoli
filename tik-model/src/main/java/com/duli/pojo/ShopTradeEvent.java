package com.duli.pojo;

import lombok.Data;

/** MQ 仅携带本事件 ID；事件类型和订单归属从数据库读取。 */
@Data
public class ShopTradeEvent {
    private String id;
    private String eventType;
    private String orderId;
    private String status;
    private int attempts;
    private String leaseToken;
    private Long consumedAt;
}
