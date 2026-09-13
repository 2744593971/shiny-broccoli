package com.duli.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.bo.CommentBO;
import com.duli.mapper.CommentMapper;
import com.duli.mapper.VlogMapper;
import com.duli.pojo.Comment;
import com.duli.pojo.Vlog;
import com.duli.service.ICommentService;
import com.duli.vo.CommentVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    @Autowired
    private CommentMapper commentMapper;


    // 🌟 注入 Redis (根据你项目实际的泛型注入，通常是 <String, String>)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private VlogMapper vlogMapper;

    // 定义 Redis Key 的前缀
    public static final String REDIS_VLOG_COMMENT_COUNTS = "redis_vlog_comment_counts";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Comment createComment(CommentBO commentBO) {
        Comment comment = new Comment();
        BeanUtils.copyProperties(commentBO, comment);

        // 初始化默认值
        comment.setLikeCounts(0);
        comment.setCreateTime(LocalDateTime.now());

        // 1. 存入 MySQL 评论表 (MyBatis-Plus 会自动通过 ASSIGN_ID 生成主键 id)
        commentMapper.insert(comment);

        // 2. Redis 视频评论总数累加 (原子性 +1，用于前端高速读取和展示)
        redisTemplate.opsForValue().increment("redis_vlog_comment_counts:" + commentBO.getVlogId(), 1);

        // 3. 数据库 vlog 表的 comments_counts 字段 +1 (用于兜底落盘，保证最终一致性)
        UpdateWrapper<Vlog> vlogUpdateWrapper = new UpdateWrapper<>();
        vlogUpdateWrapper.eq("id", commentBO.getVlogId())
                .setSql("comments_counts = comments_counts + 1");
        vlogMapper.update(null, vlogUpdateWrapper);

        // 返回新增的评论对象，前端拿到后直接追加 (push) 到列表最下方
        return comment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(String commentUserId, String commentId, String vlogId) {
        // 构建删除条件：防越权，只能删除自己的评论，且必须匹配当前视频ID
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Comment> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(Comment::getId, commentId)
                .eq(Comment::getCommentUserId, commentUserId)
                .eq(Comment::getVlogId, vlogId);

        // 1. 从 MySQL 评论表中执行物理删除，并获取受影响的行数
        int res = commentMapper.delete(wrapper);

        // 2. 只有当数据库里真的删掉了这条数据时（受影响行数 > 0），才去扣减总数
        if (res > 0) {
            // Redis 视频评论总数 -1
            redisTemplate.opsForValue().decrement("redis_vlog_comment_counts:" + vlogId, 1);

            // 数据库 vlog 表的 comments_counts 字段 -1
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.duli.pojo.Vlog> vlogUpdateWrapper = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            vlogUpdateWrapper.eq("id", vlogId)
                    .setSql("comments_counts = comments_counts - 1");
            vlogMapper.update(null, vlogUpdateWrapper);
        }
    }

    @Override
    public Page<CommentVO> queryVlogComments(String vlogId, String userId, Integer page, Integer pageSize) {
        Page<CommentVO> pageable = new Page<>(page, pageSize);
        Page<CommentVO> result = commentMapper.getCommentList(pageable, vlogId);
        
        // 此处遍历 result.getRecords() 
        // 配合 Redis 或点赞表，判断当前 userId 是否点赞了该评论，将其 isLike 设为 1 或 0
        for (CommentVO vo : result.getRecords()) {
            vo.setIsLike(0); // 暂默认未点赞，如有 redis 请在此处注入逻辑判断
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void likeComment(String userId, String commentId) {
        // 1. 数据库评论点赞数 +1
        Comment comment = commentMapper.selectById(commentId);
        comment.setLikeCounts(comment.getLikeCounts() + 1);
        commentMapper.updateById(comment);
        // 2. TODO: 可以在 Redis 记录用户点赞状态
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlikeComment(String userId, String commentId) {
        // 1. 数据库评论点赞数 -1
        Comment comment = commentMapper.selectById(commentId);
        if(comment.getLikeCounts() > 0) {
            comment.setLikeCounts(comment.getLikeCounts() - 1);
            commentMapper.updateById(comment);
        }
        // 2. TODO: 可以在 Redis 移除用户点赞状态
    }
}