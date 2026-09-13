package com.duli.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@ApiModel(value = "CommentVO对象", description = "评论列表展示对象")
public class CommentVO {

    @ApiModelProperty(value = "评论主键id", example = "123456789")
    private String commentId;

    @ApiModelProperty(value = "视频博主id", example = "10011")
    private String vlogerId;

    @ApiModelProperty(value = "父评论id(如果是回复留言则有值)", example = "0")
    private String fatherCommentId;

    @ApiModelProperty(value = "视频id", example = "20011")
    private String vlogId;

    @ApiModelProperty(value = "发布留言的用户id")
    private String commentUserId;

    @ApiModelProperty(value = "评论者昵称")
    private String commentUserNickname;

    @ApiModelProperty(value = "评论者头像")
    private String commentUserFace;

    @ApiModelProperty(value = "评论内容")
    private String content;

    @ApiModelProperty(value = "留言的点赞总数", example = "100")
    private Integer likeCounts;

    @ApiModelProperty(value = "被回复者的昵称(如果是回复他人)")
    private String replyedUserNickname;

    @ApiModelProperty(value = "当前登录用户是否点赞了该评论(1:是，0:否)", example = "0")
    private Integer isLike;

    @ApiModelProperty(value = "留言时间")
    // 🌟 企业级必备：格式化返回给前端的时间字符串，避免前端收到 ISO 标准的带 T 时间格式或时间戳
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}