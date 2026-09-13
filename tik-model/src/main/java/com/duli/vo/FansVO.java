package com.duli.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@ApiModel(value="FansVO对象", description="我的粉丝列表返回的视图对象")
public class FansVO {
    
    @ApiModelProperty(value = "粉丝的用户ID")
    private String fanId;
    
    @ApiModelProperty(value = "粉丝的昵称")
    private String nickname;
    
    @ApiModelProperty(value = "粉丝的头像")
    private String face;
    
    @ApiModelProperty(value = "是否互为好友（互相关注）")
    private boolean isFriend = false; 
}