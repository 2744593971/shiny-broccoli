package com.duli.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.enums.MessageEnum;
import com.duli.mapper.MyLikedVlogMapper;
import com.duli.mapper.VlogMapper;
import com.duli.pojo.MyLikedVlog;
import com.duli.pojo.Vlog;
import com.duli.bo.VlogBO;
import com.duli.service.IVlogService;
import com.duli.service.MsgService;
import com.duli.service.mq.MsgConsumer;
import com.duli.vo.IndexVlogVO;
import com.duli.service.IMessageOutboxService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VlogServiceImpl extends ServiceImpl<VlogMapper, Vlog> implements IVlogService {

    @Autowired
    private VlogMapper vlogMapper;

    @Autowired
    private MyLikedVlogMapper myLikedVlogMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Transactional
    @Override
    public void createVlog(VlogBO vlogBO) {
        Vlog vlog = new Vlog();
        BeanUtils.copyProperties(vlogBO, vlog);
        vlog.setLikeCounts(0);
        vlog.setCommentsCounts(0);
        vlog.setIsPrivate(0);
        vlog.setCreatedTime(LocalDateTime.now());
        vlog.setUpdatedTime(LocalDateTime.now());
        save(vlog);
    }
    /*
    后端的分页进阶：游标分页 (Cursor Pagination)
    1.后端确实是负责给数据，但在抖音这种高并发、数据实时更新的系统里，
    绝对不会使用咱们目前写的 page=1, pageSize=10 这种传统分页！
    2.传统分页的致命漏洞（数据重复）： 假设你正在看第 1 页，这时有 5 个博主发布了新视频。
    数据库里的数据会被整体往后“挤” 5 个位置。当你继续下滑请求第 2 页时，上一页末尾的 5 个旧视频刚好被挤到了第 2 页，你就会看到重复的视频。
    3.大厂解法（游标机制）： 后端不再接收 page 参数，而是让前端每次请求时，带上刚刚刷到的最后一个视频的“时间戳（Cursor）”。
    4.底层 SQL 的改变： 后端拿到时间戳后，查询语句会变成类似 WHERE created_time < #{last_time} ORDER BY created_time DESC LIMIT 10。
    这样一来，无论顶部怎么新增视频，你都是顺着时间线往下切，永远不会刷出重复数据。
    ⭐得到刷到的最后一个视频的发布时间（时间戳）然后下一页就是比这个发布时间更早的视频，而不会查到新插入的视频们
     */

    /**
     * 如果查询只涉及单表查询（比如只查vlog表，没有复杂的多表JOIN等操作），
     * 完全不需要在Mapper接口或XML文件中自己手写getIndexVlogList这样的方法。
     *  / 1. 构造分页条件
     * Page<Comment> pageable = new Page<>(page, pageSize);
     *
     * // 2. 构造查询条件 (相当于 WHERE vlog_id = ?)
     * LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
     * wrapper.eq(Comment::getVlogId, vlogId);
     *
     * // 3. ⭐直接调用自带的 selectPage (不需要自己写 mapper 方法)
     * Page<Comment> result = commentMapper.selectPage(pageable, wrapper);
     * // ⭐如果你在 Service 层，且继承了 ServiceImpl，也可以直接这样写：
     * // Page<Comment> result = this.page(pageable, wrapper);
     *
     * @param userId
     * @param search
     * @param page
     * @param pageSize
     * @return
     */
    @Override
    public Map<String, Object> getIndexVlogList(String userId, String search, Integer page, Integer pageSize) {
        Page<IndexVlogVO> pageParam = new Page<>(page, pageSize);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("search", search);
        // 🌟 核心：把 userId 传给 Mapper，用于判断点赞/关注状态
        paramMap.put("userId", userId);

        vlogMapper.getIndexVlogList(pageParam, paramMap);

        // 洗数据：补全 Redis 实时的点赞数
        setterVOs(pageParam.getRecords());

        Map<String, Object> map = new HashMap<>();
        // 1. ⭐打包总页数，告诉前端一共能翻多少页
        map.put("total", pageParam.getPages());
        // 2. ⭐打包当前页的真实视频数据列表
        map.put("rows", pageParam.getRecords());
        return map;
    }
//@Override
//public Map<String, Object> getIndexVlogList(String userId, String search, Long cursor, Integer pageSize) {
//    Map<String, Object> paramMap = new HashMap<>();
//    paramMap.put("search", search);
//    paramMap.put("userId", userId);
//
//    // 🌟 将前端传来的毫秒级游标，转换为 LocalDateTime 给 MySQL 比较
//    LocalDateTime cursorTime = LocalDateTime.ofInstant(
//            java.time.Instant.ofEpochMilli(cursor), java.time.ZoneId.systemDefault());
//    paramMap.put("cursorTime", cursorTime);
//    paramMap.put("pageSize", pageSize);
//
//    // 注意：Mapper 层不再传 Page 对象
//    List<IndexVlogVO> list = vlogMapper.getIndexVlogListByCursor(paramMap);
//    setterVOs(list); // 复用之前的洗数据逻辑
//
//    // 🌟 核心：计算下一个游标
//    Long nextCursor = null;
//    if (list != null && !list.isEmpty()) {
//        LocalDateTime lastTime = list.get(list.size() - 1).getCreatedTime();
//        nextCursor = lastTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
//    }
//
//    Map<String, Object> map = new HashMap<>();
//    map.put("rows", list);
//    map.put("nextCursor", nextCursor); // 🌟 告诉前端下次从哪开始查
//    return map;
//}

    @Override
    public Map<String, Object> getMyVlogList(String vlogerId, String currentUserId, Integer isPrivate, Integer page, Integer pageSize) {
        Page<IndexVlogVO> pageParam = new Page<>(page, pageSize);
        // 🌟 传入 currentUserId 判断状态
        vlogMapper.getMyVlogList(pageParam, vlogerId, currentUserId, isPrivate);
// 🌟🌟🌟 核心：查完之后，把 Records 丢进去洗一遍！
        setterVOs(pageParam.getRecords());
        Map<String, Object> map = new HashMap<>();
        map.put("total", pageParam.getPages());
        map.put("rows", pageParam.getRecords());
        return map;
    }

    @Override
    public Map<String, Object> getMyLikedList(String userId, String currentUserId, Integer page, Integer pageSize) {
        Page<IndexVlogVO> pageParam = new Page<>(page, pageSize);
        vlogMapper.getMyLikedList(pageParam, userId, currentUserId);
// 🌟🌟🌟 核心：查完之后，把 Records 丢进去洗一遍！
        setterVOs(pageParam.getRecords());
        Map<String, Object> map = new HashMap<>();
        map.put("total", pageParam.getPages());
        map.put("rows", pageParam.getRecords());
        return map;
    }

    @Override
    public IndexVlogVO getVlogDetailById(String userId, String vlogId) {
        List<IndexVlogVO> list = vlogMapper.getVlogDetailById(userId, vlogId);
        if (list != null && !list.isEmpty()) {
            IndexVlogVO vlogVO = list.get(0);
            // 🌟 顺手把单条视频的点赞数也补齐
            String countsStr = redisTemplate.opsForValue().get("redis_vlog_be_liked_counts:" + vlogId);
            if (org.apache.commons.lang3.StringUtils.isNotBlank(countsStr)) {
                vlogVO.setLikeCounts(Integer.valueOf(countsStr));
            }
            return vlogVO;
        }
        return null;
    }

    @Transactional
    @Override
    public void changeToPrivateOrPublic(String userId, String vlogId, Integer yesOrNo) {
        UpdateWrapper<Vlog> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", vlogId).eq("vloger_id", userId);
        Vlog updateVlog = new Vlog();
        updateVlog.setIsPrivate(yesOrNo);
        vlogMapper.update(updateVlog, updateWrapper);
    }
    @Autowired
    private MsgService msgService;
    @Autowired
    // ==================== Codex 优化：通知与业务同事务落Outbox，不在事务内访问RabbitMQ ====================
    private IMessageOutboxService messageOutbox;
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void userLikeVlog(String userId, String vlogId, String vlogerId) {
        // 1. 先查询是否已经存在这条点赞记录，防止重复插入报错
        QueryWrapper<MyLikedVlog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("vlog_id", vlogId);
        Long count = myLikedVlogMapper.selectCount(queryWrapper);

        // 2. 如果查不到记录（说明没点过赞），才允许插入和累加 Redis
        if (count == 0) {
            MyLikedVlog likedVlog = new MyLikedVlog();
            likedVlog.setUserId(userId);
            likedVlog.setVlogId(vlogId);
            myLikedVlogMapper.insert(likedVlog);

            redisTemplate.opsForValue().increment("redis_vlog_be_liked_counts:" + vlogId, 1);
            redisTemplate.opsForValue().increment("redis_vloger_be_liked_counts:" + vlogerId, 1);

            //done 验证点赞视频功能
            //3.消息功能
            Map<String,Object> msgContent = new HashMap<>();
            msgContent.put("vlogId",vlogId);
            Vlog vlog= vlogMapper.selectById(vlogId);
            if (vlog != null){
                msgContent.put("vlogCover",vlog.getCover());
            }
            MessageMQDTO mqdto = new MessageMQDTO();
            mqdto.setFromUserId(userId);
            mqdto.setToUserId(vlogerId);
            mqdto.setMsgType(MessageEnum.LIKE_VLOG.type);
            mqdto.setMsgContent(msgContent);

            // Codex 优化：点赞事务只写待发送记录，由独立任务在提交后投递。
            messageOutbox.enqueue(
                    RabbitMQConfig.EXCHANGE_MSG,
                    "sys.msg.likevlog", // 路由键控制队列
                    mqdto
            );
        }
    }

    @Transactional
    @Override
    public void userUnLikeVlog(String userId, String vlogId, String vlogerId) {
        QueryWrapper<MyLikedVlog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("vlog_id", vlogId);

        // 1. 执行删除，并获取受影响的行数
        int deletedRows = myLikedVlogMapper.delete(queryWrapper);

        // 2. 只有真正删除了记录（防止重复请求），才去递减 Redis
        if (deletedRows > 0) {
            redisTemplate.opsForValue().decrement("redis_vlog_be_liked_counts:" + vlogId, 1);
            redisTemplate.opsForValue().decrement("redis_vloger_be_liked_counts:" + vlogerId, 1);
        }
    }

    // 🌟 新增：遍历 MyBatis-Plus 查出来的列表，去 Redis 捞最新点赞数
    private void setterVOs(List<IndexVlogVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (IndexVlogVO v : list) {
            String vlogId = v.getVlogId();

            // 1. 从 Redis 获取该视频的实时总点赞数
            String likeCountsStr = redisTemplate.opsForValue().get("redis_vlog_be_liked_counts:" + vlogId);
            if (org.apache.commons.lang3.StringUtils.isNotBlank(likeCountsStr)) {
                v.setLikeCounts(Integer.valueOf(likeCountsStr)); // 覆盖 MySQL 里的点赞旧数据
            }

            // 🌟 2. 新增：从 Redis 获取该视频的实时总评论数
            String commentCountsStr = redisTemplate.opsForValue().get("redis_vlog_comment_counts:" + vlogId);
            if (org.apache.commons.lang3.StringUtils.isNotBlank(commentCountsStr)) {
                v.setCommentsCounts(Integer.valueOf(commentCountsStr)); // 覆盖 MySQL 里的评论旧数据
            }
        }
    }

    @Override
    public Integer getVlogBeLikedCounts(String vlogId) {
        // 直接去 Redis 捞取实时的点赞数
        String countsStr = redisTemplate.opsForValue().get("redis_vlog_be_liked_counts:" + vlogId);
        if (org.apache.commons.lang3.StringUtils.isBlank(countsStr)) {
            countsStr = "0"; // 如果没查到，默认就是 0
        }
        return Integer.valueOf(countsStr);
    }

    @Override
    public Map<String, Object> getMyFollowVlogList(String myId, Integer page, Integer pageSize) {
        Page<IndexVlogVO> pageParam = new Page<>(page, pageSize);

        // 调用 Mapper 去查底层 SQL
        vlogMapper.getMyFollowVlogList(pageParam, myId);

        // 🌟 核心：查完之后，把 Records 丢进去洗一遍 Redis 的点赞数！
        setterVOs(pageParam.getRecords());

        Map<String, Object> map = new HashMap<>();
        map.put("total", pageParam.getPages());
        map.put("rows", pageParam.getRecords());
        return map;
    }

    @Override
    public Map<String, Object> getMyFriendVlogList(String myId, Integer page, Integer pageSize) {
        Page<IndexVlogVO> pageParam = new Page<>(page, pageSize);

        vlogMapper.getMyFriendVlogList(pageParam, myId);

        // 核心：复用我们之前的洗数据逻辑，补全 Redis 点赞数
        setterVOs(pageParam.getRecords());

        Map<String, Object> map = new HashMap<>();
        map.put("total", pageParam.getPages());
        map.put("rows", pageParam.getRecords());
        return map;
    }

}
