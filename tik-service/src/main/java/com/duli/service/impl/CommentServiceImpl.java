package com.duli.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.bo.CommentBO;
import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.enums.MessageEnum;
import com.duli.mapper.CommentMapper;
import com.duli.mapper.VlogMapper;
import com.duli.pojo.Comment;
import com.duli.pojo.Vlog;
import com.duli.service.ICommentService;
import com.duli.service.MsgService;
import com.duli.vo.CommentVO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    @Autowired
    private CommentMapper commentMapper;


    // 🌟 注入 Redis (根据你项目实际的泛型注入，通常是 <String, String>)
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private VlogMapper vlogMapper;

    @Autowired
    private MsgService msgService;
    // 注入 RabbitTemplate，不再直接注入 MsgService,实现解耦
    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 定义 Redis Key 的前缀：记录视频评论总数
    public static final String REDIS_VLOG_COMMENT_COUNTS = "redis_vlog_comment_counts";
    // 🌟 记录用户点赞过的评论
    public static final String REDIS_USER_LIKE_COMMENT = "redis_user_like_comment";
    // 🌟 记录每条评论的总点赞数 (String)
    public static final String REDIS_COMMENT_LIKE_COUNTS = "redis_comment_like_counts";

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

        //done 验证评论和回复评论功能
        //消息功能
        Map<String, Object> msgContent = new HashMap<>();
        msgContent.put("vlogId", commentBO.getVlogId());
        msgContent.put("commentContent", commentBO.getContent());
        msgContent.put("commentId", comment.getId());

        // 注意：前端消息列表可能需要展示视频封面，这里需要你去查一下
        Vlog vlog = vlogMapper.selectById(commentBO.getVlogId());
        if (vlog != null) {
            msgContent.put("vlogCover", vlog.getCover());
        }
        // (3) 核心路由：根据 fatherCommentId 判断消息类型
//        if (StringUtils.isBlank(commentBO.getFatherCommentId())
//                || "0".equalsIgnoreCase(commentBO.getFatherCommentId())) {
//
//            // 情况 A：直接评论视频 -> 发给视频博主 (vlogerId)，类型为 COMMENT_VLOG (3)
//            msgService.createMsg(commentBO.getCommentUserId(), commentBO.getVlogerId(), MessageEnum.COMMENT_VLOG, msgContent);
//        } else {
//
//            // 情况 B：回复评论 -> 发给被回复的那条评论的作者，类型为 REPLY_YOU (4)
//            // 先去数据库查出被回复的那条评论是谁发的
//            Comment fatherComment = baseMapper.selectById(commentBO.getFatherCommentId());
//            if (fatherComment != null) {
//                msgService.createMsg(commentBO.getCommentUserId(),
//                        fatherComment.getCommentUserId(), // 接收者是父评论的作者
//                        MessageEnum.REPLY_YOU,
//                        msgContent
//                );
//            }
//        }
//
//        // 返回新增的评论对象，前端拿到后直接追加 (push) 到列表最下方
//        return comment;
        //⭐mq解耦
        // (3) 核心路由：根据 fatherCommentId 判断消息类型
        if (StringUtils.isBlank(commentBO.getFatherCommentId())
                || "0".equalsIgnoreCase(commentBO.getFatherCommentId())) {

            // 情况 A：直接评论视频 -> 发给视频博主 (vlogerId)，类型为 COMMENT_VLOG (3)
            // 🌟 核心改造：使用 MQ 解耦
            MessageMQDTO mqdto = new MessageMQDTO();
            mqdto.setFromUserId(commentBO.getCommentUserId());
            mqdto.setToUserId(commentBO.getVlogerId());
            mqdto.setMsgType(MessageEnum.COMMENT_VLOG.type);
            mqdto.setMsgContent(msgContent);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_MSG,
                    "sys.msg.comment", // 路由键
                    mqdto
            );

        } else {

            // 情况 B：回复评论 -> 发给被回复的那条评论的作者，类型为 REPLY_YOU (4)
            Comment fatherComment = baseMapper.selectById(commentBO.getFatherCommentId());
            if (fatherComment != null) {
                // 🌟 核心改造：使用 MQ 解耦
                MessageMQDTO mqdto = new MessageMQDTO();
                mqdto.setFromUserId(commentBO.getCommentUserId());
                mqdto.setToUserId(fatherComment.getCommentUserId()); // 接收者是父评论的作者
                mqdto.setMsgType(MessageEnum.REPLY_YOU.type);
                mqdto.setMsgContent(msgContent);

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_MSG,
                        "sys.msg.reply", // 路由键
                        mqdto
                );
            }
        }
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
            UpdateWrapper<Vlog> vlogUpdateWrapper = new UpdateWrapper<>();
            vlogUpdateWrapper.eq("id", vlogId)
                    .setSql("comments_counts = comments_counts - 1");
            vlogMapper.update(null, vlogUpdateWrapper);
        }
    }

    @Override
    public Page<CommentVO> queryVlogComments(String vlogId, String userId, Integer page, Integer pageSize) {
        //不仅仅是“声明”一下page, pageSize这两个参数，它其实也是在创建一个“双向数据载体（或者叫空盒子）”。
        //result是装数据的实盒子，pageable是空盒子    内存地址是同一个
        Page<CommentVO> pageable = new Page<>(page, pageSize);
        // 从 MySQL 查出基础数据，此时 vo.getLikeCounts() 是滞后的
        //commentMapper.getCommentList(pageable, vlogId);也一样可以，不需要返回值
        Page<CommentVO> result = commentMapper.getCommentList(pageable, vlogId);

        for (CommentVO vo : result.getRecords()) {//getRecords() 就是单纯地把那一批真正的数据列表（也就是 List 集合）给拿出来
            String commentId = vo.getCommentId();
            // 🌟 1. 从 Redis 获取该评论的最新的总点赞数，覆盖 MySQL 里的旧数据
            String likeCountsStr = redisTemplate.opsForValue().get(REDIS_COMMENT_LIKE_COUNTS + ":" + commentId);
            if (StringUtils.isNotBlank(likeCountsStr)) {
                vo.setLikeCounts(Integer.valueOf(likeCountsStr));
            }
            // 2. 判断当前 userId 是否点赞了该评论
            vo.setIsLike(0);
            if (StringUtils.isNotBlank(userId)) {
                Boolean isMember = redisTemplate.opsForSet().isMember(REDIS_USER_LIKE_COMMENT + ":" + userId, commentId);
                if (isMember != null && isMember) {//如果点了赞
                    vo.setIsLike(1);
                }
            }
        }

        return result;
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void likeComment(String userId, String commentId) {
//        // 1. 数据库评论点赞数 +1
//        Comment comment = commentMapper.selectById(commentId);
//        comment.setLikeCounts(comment.getLikeCounts() + 1);
//        commentMapper.updateById(comment);
//        // 🌟 2. 在 Redis 中记录用户点赞状态：把 commentId 存入该用户的 点赞评论Set 集合中
//        redisTemplate.opsForSet().add(REDIS_USER_LIKE_COMMENT + ":" + userId, commentId);
//        //在redis的这个集合里value就是一堆存放着 commentId的集合（一堆无序且不重复的字符串）。
//    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void unlikeComment(String userId, String commentId) {
//        // 1. 数据库评论点赞数 -1
//        Comment comment = commentMapper.selectById(commentId);
//        if(comment.getLikeCounts() > 0) {
//            comment.setLikeCounts(comment.getLikeCounts() - 1);
//            commentMapper.updateById(comment);
//        }
//        // 2.在 Redis 移除用户点赞状态
//        redisTemplate.opsForSet().remove(REDIS_USER_LIKE_COMMENT + ":" + userId, commentId);
//    }
    //⭐改造点赞和取消功能： 用redis存+mysql洗数据方式

@Override
@Transactional(rollbackFor = Exception.class)
public void likeComment(String userId, String commentId) {
    // 1. 在 Redis 中记录用户点赞状态 (存入 Set)
    redisTemplate.opsForSet().add(REDIS_USER_LIKE_COMMENT + ":" + userId, commentId);

    // 🌟 2. 评论总点赞数在 Redis 中累加 +1 (完全脱离 MySQL 行锁)
    redisTemplate.opsForValue().increment(REDIS_COMMENT_LIKE_COUNTS + ":" + commentId, 1);

    Comment comment=commentMapper.selectById(commentId);
    // 🛡️ 防御性编程：确保评论真的存在（防止用户点赞瞬间，评论刚好被作者删了）
    if (comment != null) {
        Map<String, Object> msgContent = new HashMap<>();
        msgContent.put("vlogId", comment.getVlogId());
        msgContent.put("commentId", commentId); // 建议顺手把 commentId 也放进去，方便前端后续可能需要的精准跳转

        // 2. 查出对应视频获取封面
        Vlog vlog = vlogMapper.selectById(comment.getVlogId());
        if (vlog != null) {
            msgContent.put("vlogCover", vlog.getCover());
        }

        // 3. 发送点赞评论的消息
        // 发送方: userId (点赞的人)
        // 接收方: comment.getCommentUserId() (写这条评论的人)
        // 🌟 核心改造：封装 DTO 并发往 RabbitMQ，不在此处直接操作 MongoDB
        MessageMQDTO mqdto = new MessageMQDTO();
        mqdto.setFromUserId(userId);
        mqdto.setToUserId(comment.getCommentUserId());
        mqdto.setMsgType(MessageEnum.LIKE_COMMENT.type);
        mqdto.setMsgContent(msgContent);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_MSG,
                "sys.msg.like", // RoutingKey，符合 sys.msg.# 的规则即可
                mqdto
        );
    }
    //done 点赞评论验证完成
//    Vlog vlog = vlogMapper.selectById(comment.getVlogId());
//    Map<String,Object> msgContent=new HashMap<>();
//    if(vlog!=null){
//        msgContent.put("vlogId",comment.getVlogId());
//        msgContent.put("vlogCover",vlog.getCover());
//    }
//    msgService.createMsg(userId,comment.getCommentUserId(),MessageEnum.LIKE_COMMENT,msgContent);
}

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlikeComment(String userId, String commentId) {
        // 1. 在 Redis 中移除用户点赞状态 (从 Set 剔除)
        redisTemplate.opsForSet().remove(REDIS_USER_LIKE_COMMENT + ":" + userId, commentId);

        // 🌟 2. 评论总点赞数在 Redis 中递减 -1
        redisTemplate.opsForValue().decrement(REDIS_COMMENT_LIKE_COUNTS + ":" + commentId, 1);
    }

}