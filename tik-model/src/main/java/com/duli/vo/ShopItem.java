package com.duli.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 商城展示对象：商品信息与可选秒杀信息放在一起，避免前端再请求多次。
 * 金额使用 BigDecimal，禁止用 double 参与订单金额计算。
 * 时间为 Unix 毫秒；前端用 serverTime 校准倒计时，但最终资格由数据库时间裁决。
 */
@Data
public class ShopItem {
    private String id;
    private String title;
    private String description;
    private String cover;
    private BigDecimal price;
    private int stock;
    private String activityId;
    private String activityTitle;
    private BigDecimal seckillPrice;
    private Integer activityStock;
    private Long startsAt;
    private Long endsAt;
}
