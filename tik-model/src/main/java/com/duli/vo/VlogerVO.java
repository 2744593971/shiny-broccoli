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
@ApiModel(value="VlogerVO对象", description="我的关注列表返回的视图对象")
public class VlogerVO {
    
    @ApiModelProperty(value = "博主的用户ID")
    private String vlogerId;
    
    @ApiModelProperty(value = "博主的昵称")
    private String nickname;
    
    @ApiModelProperty(value = "博主的头像")
    private String face;
    
    @ApiModelProperty(value = "是否已关注该博主 (默认 true)")
    private boolean isFollowed = true; 
}