package com.duli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duli.pojo.Comment;
import org.apache.ibatis.annotations.Mapper;

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

}
