package com.duli.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 用户表
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("users")
@ApiModel(value="Users对象", description="用户表")
public class Users implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @ApiModelProperty(value = "手机号")
    private String mobile;

    @ApiModelProperty(value = "昵称，媒体号")
    private String nickname;

    @ApiModelProperty(value = "慕课号，类似头条号，抖音号，公众号，唯一标识，需要限制修改次数，比如终生1次，每年1次，每半年1次等，可以用于付费修改。")
    private String imoocNum;

    @ApiModelProperty(value = "头像")
    private String face;

    @ApiModelProperty(value = "性别 1:男  0:女  2:保密")
    private Integer sex;

    @ApiModelProperty(value = "生日")
    private LocalDate birthday;

    @ApiModelProperty(value = "国家")
    private String country;

    @ApiModelProperty(value = "省份")
    private String province;

    @ApiModelProperty(value = "城市")
    private String city;

    @ApiModelProperty(value = "区县")
    private String district;

    @ApiModelProperty(value = "简介")
    private String description;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "个人介绍的背景图")
    private String bgImg;

    @ApiModelProperty(value = "慕课号能否被修改，1：默认，可以修改；0，无法修改")
    private Integer canImoocNumBeUpdated;

    @ApiModelProperty(value = "创建时间 创建时间")
    private LocalDateTime createdTime;

    @ApiModelProperty(value = "更新时间 更新时间")
    private LocalDateTime updatedTime;


}
