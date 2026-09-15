package com.duli.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.duli.pojo.Comment;
import com.duli.service.ICommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CommentTask {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ICommentService commentService;

    /**
     * 定时任务：将 Redis 中的评论点赞数同步到 MySQL
     * cron = "0/30 * * * * ?" 表示每 30 秒执行一次（为了方便测试），生产环境建议调长
     */
    @Scheduled(cron = "0/30 * * * * ?")
    public void syncCommentLikedCounts() {
        // 1. 从 Redis 模糊匹配捞取所有评论点赞数的 key
        Set<String> keys = redisTemplate.keys("redis_comment_like_counts:*");
        
        if (keys == null || keys.isEmpty()) {
            return;
        }

        // 2. 遍历这些 key，逐个解析出 commentId 和点赞数
        for (String key : keys) {
            // key 的格式是 "redis_comment_like_counts:评论ID"，截取后半部分
            String commentId = key.split(":")[1];
            String countsStr = redisTemplate.opsForValue().get(key);
            
            if (org.apache.commons.lang3.StringUtils.isNotBlank(countsStr)) {
                int counts = Integer.parseInt(countsStr);
                
                // 3. 执行 MySQL 的 Update 语句
                UpdateWrapper<Comment> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", commentId);
                
                Comment updateComment = new Comment();
                updateComment.setLikeCounts(counts);
                
                // 相当于 SQL: update comment set like_counts = #{counts} where id = #{commentId}
                commentService.update(updateComment, updateWrapper);
            }
        }
        System.out.println("🌟 同步执行完毕：评论点赞数已落盘到 MySQL 数据库！");
    }
}