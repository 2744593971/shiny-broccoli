package com.duli.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.enums.MessageEnum;
import com.duli.mapper.FansMapper;
import com.duli.pojo.Fans;
import com.duli.service.IFansService;
import com.duli.service.MsgService;
import com.duli.vo.FansVO;
import com.duli.vo.VlogerVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.List;


@Service
public class FansServiceImpl extends ServiceImpl<FansMapper, Fans> implements IFansService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private FansMapper fansMapper;
    @Autowired
    private MsgService msgService;
    @Override
    public boolean queryDoIFollowVloger(String myId, String vlogerId) {
        Fans fan = getSingleFan(myId, vlogerId);
        return fan != null;
    }

    @Transactional
    @Override
    public void doFollow(String myId, String vlogerId) {
        // 0. 防重校验：如果我已经关注了对方，直接结束，防止重复插入报错 (解决 DuplicateKeyException)
        if (queryDoIFollowVloger(myId, vlogerId)) {
            return;
        }

        // 1. 先判断对方是否已经关注了我
        QueryWrapper<Fans> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("vloger_id", myId).eq("fan_id", vlogerId);
        Fans vlogerFollowMeRecord = fansMapper.selectOne(queryWrapper);

        // 2. 构造我关注对方的记录
        Fans fans = new Fans();
        fans.setFanId(myId);
        fans.setVlogerId(vlogerId);

        // 🌟 核心提取：根据对方有没有关注我，直接得出是不是互关状态 (isFriend)
        boolean isFriend = (vlogerFollowMeRecord != null);

        if (isFriend) {
            // 对方已经关注了我，说明这是“双向奔赴”
            fans.setIsFanFriendOfMine(1);
            vlogerFollowMeRecord.setIsFanFriendOfMine(1);
            fansMapper.updateById(vlogerFollowMeRecord);
        } else {
            // 对方没关注我，说明只是我单方面关注
            fans.setIsFanFriendOfMine(0);
        }

        // 3. 把我的关注记录插入数据库
        fansMapper.insert(fans);

        // 4. Redis 缓存累加逻辑
        redisTemplate.opsForValue().increment("redis_my_follows_counts:" + myId, 1);
        redisTemplate.opsForValue().increment("redis_my_fans_counts:" + vlogerId, 1);

        // 5. 发送 MongoDB 消息通知
        Map<String, Object> msgContent = new HashMap<>();
        // 直接复用上面的 isFriend 变量，省去了一次查数据库的操作！
        msgContent.put("isFriend", isFriend);

        msgService.createMsg(myId, vlogerId, MessageEnum.FOLLOW_YOU, msgContent);
    }

    @Transactional
    @Override
    public void doCancel(String myId, String vlogerId) {
        // 0. 防重校验：如果我根本就没有关注对方，直接结束！防止 Redis 扣成负数
        if (!queryDoIFollowVloger(myId, vlogerId)) {
            return;
        }

        // 1. 删除我关注对方的记录
        QueryWrapper<Fans> deleteWrapper = new QueryWrapper<>();
        deleteWrapper.eq("vloger_id", vlogerId).eq("fan_id", myId);
        fansMapper.delete(deleteWrapper);

        // 2. 判断对方是否还关注着我
        QueryWrapper<Fans> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("vloger_id", myId).eq("fan_id", vlogerId);
        Fans vlogerFollowMeRecord = fansMapper.selectOne(queryWrapper);

        if (vlogerFollowMeRecord != null) {
            // 🌟 对方还在关注我，但是我已经取关了，所以要打破互粉状态
            vlogerFollowMeRecord.setIsFanFriendOfMine(0);
            fansMapper.updateById(vlogerFollowMeRecord);
        }

        // 3. Redis 缓存递减逻辑
        redisTemplate.opsForValue().decrement("redis_my_follows_counts:" + myId, 1);
        redisTemplate.opsForValue().decrement("redis_my_fans_counts:" + vlogerId, 1);
        
    }

    /**
     * 辅助方法：抽取公共的查询逻辑
     * @param fanId 粉丝ID
     * @param vlogerId 博主ID
     * @return 返回单条关注记录
     */
    private Fans getSingleFan(String fanId, String vlogerId) {
        QueryWrapper<Fans> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("fan_id", fanId)
                    .eq("vloger_id", vlogerId);
        return this.getOne(queryWrapper);
    }

    @Override
    public Map<String, Object> queryMyFollows(String myId, Integer page, Integer pageSize) {
        // 1. 开启分页拦截
        PageHelper.startPage(page, pageSize);
        // 2. 执行连表查询
        List<VlogerVO> list = baseMapper.queryMyFollows(myId);
        // 3. 获取分页数据
        PageInfo<?> pageList = new PageInfo<>(list);

        // 4. 封装成前端能够直接解析的 rows 和 total 结构
        Map<String, Object> map = new HashMap<>();
        map.put("rows", list);
        map.put("total", pageList.getPages()); // 返回总页数
        return map;
    }

    @Override
    public Map<String, Object> queryMyFans(String myId, Integer page, Integer pageSize) {
        PageHelper.startPage(page, pageSize);
        List<FansVO> list = baseMapper.queryMyFans(myId);
        PageInfo<?> pageList = new PageInfo<>(list);

        Map<String, Object> map = new HashMap<>();
        map.put("rows", list);
        map.put("total", pageList.getPages());
        return map;
    }
}