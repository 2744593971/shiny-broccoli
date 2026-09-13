package com.duli.vo;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
@ApiModel(value="IndexVlogVO对象", description="视频")
@Data
public class IndexVlogVO {
    private String id;           // 🌟 迎合当前页面：vlog.id
    private String vlogId;       // 迎合搜索页面：vlog.vlogId
    private String vlogerId;
    private String vlogerName;
    private String vlogerFace;
    private String content;
    private String cover;
    private String url;
    private Integer width;
    private Integer height;
    private Integer isPrivate;
    private Integer likeCounts;      // 视频点赞数
    private Integer commentsCounts;  // 视频评论数
    private Boolean doILikeThisVlog; // 当前用户是否喜欢该视频
    @ApiModelProperty("当前用户是否关注了该博主")
    private Boolean doIFollowVloger; // 当前用户是否关注了该博主

    //private java.time.LocalDateTime createdTime;
}