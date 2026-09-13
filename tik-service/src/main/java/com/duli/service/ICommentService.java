package com.duli.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.duli.bo.CommentBO;
import com.duli.pojo.Comment;
import com.duli.vo.CommentVO;

/**
 * <p>
 * 评论表 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
public interface ICommentService extends IService<Comment> {
    Comment createComment(CommentBO commentBO);

    Page<CommentVO> queryVlogComments(String vlogId, String userId, Integer page, Integer pageSize);

    void deleteComment(String commentUserId, String commentId, String vlogId);

    void likeComment(String userId, String commentId);

    void unlikeComment(String userId, String commentId);

}
