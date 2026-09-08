package com.duli.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.mapper.CommentMapper;
import com.duli.pojo.Comment;
import com.duli.service.ICommentService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 评论表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

}
