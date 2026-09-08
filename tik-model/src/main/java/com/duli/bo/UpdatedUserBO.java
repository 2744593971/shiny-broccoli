package com.duli.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Date;

@ApiModel(value = "用户修改信息入参 (UpdatedUserBO)", description = "用于接收前端传递的修改用户信息的对象")
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class UpdatedUserBO {

    @ApiModelProperty(value = "用户ID（后端自动从Token获取）", hidden = true)
    private String id;

    @ApiModelProperty(value = "昵称", example = "张三", required = true)
    //@NotBlank(message = "昵称不能为空")
    @Length(max = 16, message = "昵称长度不能超过16位")
    private String nickname;

    @ApiModelProperty(value = "抖音号", example = "imooc_123", required = true)
    //@NotBlank(message = "抖音号不能为空")
    @Length(max = 16, message = "抖音号长度不能超过16位")
    private String imoocNum;

    @ApiModelProperty(value = "用户头像地址", example = "http://xxx.com/face.jpg", required = true)
    //@NotBlank(message = "头像不能为空")
    private String face;

    @ApiModelProperty(value = "性别", example = "1", notes = "0:女, 1:男, 2:保密", required = true)
    //@NotNull(message = "性别不能为空")
    @Min(value = 0, message = "性别选择不正确")
    @Max(value = 2, message = "性别选择不正确")
    private Integer sex;

    @ApiModelProperty(value = "生日", example = "2000-01-01", required = true)
    //@NotNull(message = "生日不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthday;

    @ApiModelProperty(value = "国家", example = "中国")
    private String country;

    @ApiModelProperty(value = "省份", example = "北京")
    private String province;

    @ApiModelProperty(value = "城市", example = "北京")
    private String city;

    @ApiModelProperty(value = "区县", example = "朝阳区")
    private String district;

    @ApiModelProperty(value = "个人简介", example = "这是一个热爱生活的人", required = true)
   // @NotBlank(message = "个人简介不能为空")
    @Length(max = 100, message = "个人简介不能超过100字")
    private String description;

    @ApiModelProperty(value = "背景图地址", example = "http://xxx.com/bg.jpg")
    private String bgImg;

    @ApiModelProperty(value = "抖音号是否可修改", example = "1", notes = "0:不可修改, 1:可修改", required = true)
    //@NotNull(message = "抖音号修改权限状态不能为空")
    private Integer canImoocNumBeUpdated;
}