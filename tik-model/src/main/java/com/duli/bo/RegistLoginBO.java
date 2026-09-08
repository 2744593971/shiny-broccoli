package com.duli.bo;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
//BO 的全称是 Business Object（业务对象）
// 专门用来封装某个具体“业务场景”(登录注册)所需的数据
@AllArgsConstructor
@NoArgsConstructor
@Data // 自动生成 getter/setter/toString 等方法
public class RegistLoginBO {

    // @NotBlank 是 validation 校验注解，代表这个字符串不能为 null，也不能全是空格
    @NotBlank(message = "手机号不能为空")
    // 你还可以加上正则表达式校验，防止前端乱传（可选）
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @ApiModelProperty(value = "手机号", name = "mobile", example = "19211522796", required = true)
    private String mobile;

    @NotBlank(message = "验证码不能为空")
    @ApiModelProperty(value = "验证码", name = "smsCode", example = "123456", required = true)
    private String smsCode;
    
    // 如果以后前端登录时需要传手机设备号等，直接在这里加字段即可，不需要改 Controller 方法签名
    // private String deviceId;
}