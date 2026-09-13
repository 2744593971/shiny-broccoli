package com.duli.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duli.bo.CommentBO;
import com.duli.grace.result.GraceJSONResult;
import com.duli.pojo.Comment;
import com.duli.service.ICommentService;
import com.duli.vo.CommentVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Api(tags = "评论模块接口")
@RestController
@RequestMapping("/comment")
public class CommentController {

    @Autowired
    private ICommentService commentService;

    @ApiOperation(value = "发表或回复评论")
    @PostMapping("/create")
    public GraceJSONResult createComment(@RequestBody CommentBO commentBO) {
        Comment comment = commentService.createComment(commentBO);
        return GraceJSONResult.success(comment);
    }

    @ApiOperation(value = "查询视频的评论列表")
    @GetMapping("/list")
    public GraceJSONResult commentList(@RequestParam String vlogId,
                                       @RequestParam(required = false) String userId,
                                       @RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        
        Page<CommentVO> gridResult = commentService.queryVlogComments(vlogId, userId, page, pageSize);
        
        Map<String, Object> map = new HashMap<>();
        map.put("rows", gridResult.getRecords());
        map.put("total", gridResult.getPages());
        map.put("records", gridResult.getTotal());
        
        return GraceJSONResult.success(map);
    }

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public static final String REDIS_VLOG_COMMENT_COUNTS = "redis_vlog_comment_counts";

    @ApiOperation(value = "获得视频总评论数")
    @GetMapping("/counts")
    public GraceJSONResult commentCounts(@RequestParam String vlogId) {
        // 🌟 1. 优先从 Redis 中获取最新的总数
        String countStr = redisTemplate.opsForValue().get(REDIS_VLOG_COMMENT_COUNTS + ":" + vlogId);

        // 2. 如果 Redis 里为空（说明是第一次查，或者缓存过期了）
        if (StringUtils.isBlank(countStr)) {
            // 去 MySQL 数据库兜底查一次
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.duli.pojo.Comment> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            queryWrapper.eq(com.duli.pojo.Comment::getVlogId, vlogId);
            long dbCount = commentService.count(queryWrapper);

            // 查完后同步写回 Redis
            redisTemplate.opsForValue().set(REDIS_VLOG_COMMENT_COUNTS + ":" + vlogId, String.valueOf(dbCount));
            return GraceJSONResult.success((int) dbCount);
        }

        // 🌟 3. 如果 Redis 里有数据，直接返回，绝对没有延迟！
        return GraceJSONResult.success(Integer.valueOf(countStr));
    }

    @ApiOperation(value = "删除评论")
    @DeleteMapping("/delete")
    public GraceJSONResult deleteComment(@RequestParam String commentUserId,
                                         @RequestParam String commentId,
                                         @RequestParam String vlogId) {
        commentService.deleteComment(commentUserId, commentId, vlogId);
        return GraceJSONResult.success();
    }

    @ApiOperation(value = "点赞评论")
    @PostMapping("/like")
    public GraceJSONResult likeComment(@RequestParam String userId,
                                       @RequestParam String commentId) {
        commentService.likeComment(userId, commentId);
        return GraceJSONResult.success();
    }

    @ApiOperation(value = "取消点赞评论")
    @PostMapping("/unlike")
    public GraceJSONResult unlikeComment(@RequestParam String userId,
                                         @RequestParam String commentId) {
        commentService.unlikeComment(userId, commentId);
        return GraceJSONResult.success();
    }
}