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
import java.time.LocalDateTime;

/**
 * <p>
 * 短视频表
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("vlog")
@ApiModel(value="Vlog对象", description="短视频表")
public class Vlog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @ApiModelProperty(value = "对应用户表id，vlog视频发布者")
    private String vlogerId;

    @ApiModelProperty(value = "视频播放地址")
    private String url;

    @ApiModelProperty(value = "视频封面")
    private String cover;

    @ApiModelProperty(value = "视频标题，可以为空")
    private String title;

    @ApiModelProperty(value = "视频width")
    private Integer width;

    @ApiModelProperty(value = "视频height")
    private Integer height;

    @ApiModelProperty(value = "点赞总数")
    private Integer likeCounts;

    @ApiModelProperty(value = "评论总数")
    private Integer commentsCounts;

    @ApiModelProperty(value = "是否私密，用户可以设置私密，如此可以不公开给比人看")
    private Integer isPrivate;

    @ApiModelProperty(value = "创建时间 创建时间")
    private LocalDateTime createdTime;

    @ApiModelProperty(value = "更新时间 更新时间")
    private LocalDateTime updatedTime;


}
