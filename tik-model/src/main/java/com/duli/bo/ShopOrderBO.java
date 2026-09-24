package com.duli.bo;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 客户端只能选择商品/活动，不能提交用户身份、金额或购买数量。
 * requestId 是同一次购买意图的幂等键：超时重试必须沿用，而新的一单应生成新键。
 */
@Data
public class ShopOrderBO {
    @NotBlank(message = "请选择商品")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "商品编号格式错误")
    private String productId;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "活动编号格式错误")
    // 有 activityId 才走秒杀活动时间/配额校验；空值表示普通购买。
    private String activityId;

    @NotBlank(message = "缺少请求编号")
    @Pattern(regexp = "[A-Za-z0-9_-]{16,64}", message = "请求编号格式错误")
    private String requestId;
    // 收货信息作为订单快照，后续修改表单不会影响已经生成的订单。
    @NotBlank @Size(max=40)
    private String receiverName;
    @NotBlank @Pattern(regexp="[0-9+ -]{6,20}")
    private String receiverPhone;
    @NotBlank @Size(min=5,max=300)
    private String receiverAddress;
}
