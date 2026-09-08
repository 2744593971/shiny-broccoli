package com.duli.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDate;

@Data
@ApiModel(value = "用户信息VO", description = "登录成功后返回给前端的用户数据")
public class UserVO {

    @ApiModelProperty(value = "主键ID")
    private String id;

    @ApiModelProperty(value = "手机号")
    private String mobile;

    @ApiModelProperty(value = "昵称，媒体号")
    private String nickname;

    @ApiModelProperty(value = "慕课号")
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

    @ApiModelProperty(value = "个人介绍的背景图")
    private String bgImg;

    @ApiModelProperty(value = "慕课号能否被修改，1：默认，可以修改；0，无法修改")
    private Integer canImoocNumBeUpdated;

    // 👇 重点：这是实体类里没有的，专门为了给前端发通行证加的字段
    @ApiModelProperty(value = "用户的 JWT Token")
    private String userToken;

    @ApiModelProperty(value = "用户的默认邮箱")
    private String email;

    @ApiModelProperty(value = "用户的关注数")
    private Integer myFollowsCounts;
    @ApiModelProperty(value = "用户的粉丝数")
    private Integer myFansCounts;
    //    private Integer myLikedVlogCounts;
    @ApiModelProperty(value = "用户的获赞数")
    private Integer totalLikeMeCounts;

}