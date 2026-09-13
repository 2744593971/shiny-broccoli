package com.duli.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.duli.pojo.Vlog;
import com.duli.service.IVlogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class VlogTask {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IVlogService vlogService;

    /**
     * 定时任务：将 Redis 中的视频点赞数同步到 MySQL
     * cron = "0/30 * * * * ?" 表示每 30 秒执行一次（为了方便你现在测试效果）
     * 实际生产环境中，可以改成 "0 0 * * * ?"（每小时执行一次）或者更长周期
     */
    @Scheduled(cron = "0/30 * * * * ?")
    public void syncVlogLikedCounts() {
        // 1. 从 Redis 模糊匹配捞取所有视频点赞数的 key
        Set<String> keys = redisTemplate.keys("redis_vlog_be_liked_counts:*");
        
        if (keys == null || keys.isEmpty()) {
            return;
        }

        // 2. 遍历这些 key，逐个解析出 vlogId 和点赞数
        for (String key : keys) {
            // key 的格式是 "redis_vlog_be_liked_counts:视频ID"，截取后半部分
            String vlogId = key.split(":")[1];
            String countsStr = redisTemplate.opsForValue().get(key);
            
            if (org.apache.commons.lang3.StringUtils.isNotBlank(countsStr)) {
                int counts = Integer.parseInt(countsStr);
                
                // 3. 执行 MySQL 的 Update 语句
                UpdateWrapper<Vlog> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", vlogId);
                
                Vlog updateVlog = new Vlog();
                updateVlog.setLikeCounts(counts);
                
                // 相当于 SQL: update vlog set like_counts = #{counts} where id = #{vlogId}
                vlogService.update(updateVlog, updateWrapper);
            }
        }
        System.out.println("🌟 同步执行完毕：视频点赞数已落盘到 MySQL 数据库！");
    }
}