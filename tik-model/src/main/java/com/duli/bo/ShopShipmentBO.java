package com.duli.bo;
import lombok.Data;
import javax.validation.constraints.*;
/** 管理员手动发货信息；物流轨迹暂不对接外部快递接口。 */
@Data
public class ShopShipmentBO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") private String orderId;
    @NotBlank @Size(max=60) private String carrier;
    @NotBlank @Pattern(regexp="[A-Za-z0-9-]{4,64}") private String trackingNo;
}
