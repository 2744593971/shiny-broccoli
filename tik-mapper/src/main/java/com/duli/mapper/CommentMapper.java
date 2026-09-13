package com.duli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duli.pojo.Comment;
import com.duli.vo.CommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
/**
 * <p>
 * 评论表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
public interface CommentMapper extends BaseMapper<Comment> {
    // 联查评论列表（含用户信息及被回复人信息）
    Page<CommentVO> getCommentList(Page<CommentVO> page, @Param("vlogId") String vlogId);

}
