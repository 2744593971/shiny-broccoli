package com.duli.service.impl;

import com.duli.enums.MessageEnum;
import com.duli.mapper.FansMapper;
import com.duli.mo.MessageMO;
import com.duli.pojo.Users;
import com.duli.service.IUsersService;
import com.duli.service.MsgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MsgServiceImpl implements MsgService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IUsersService usersService;

    @Override
    public void createMsg(String fromUserId, String toUserId, MessageEnum msgEnum, Map<String, Object> msgContent) {
        MessageMO messageMO = new MessageMO();
        messageMO.setFromUserId(fromUserId);
        messageMO.setToUserId(toUserId);
        messageMO.setMsgType(msgEnum.type);
        messageMO.setMsgContent(msgContent);
        messageMO.setCreateTime(new Date());

        // --- 核心新增逻辑：查询发送者的用户信息并赋值 ---

        Users user = usersService.getById(fromUserId);
        if (user != null) {
            messageMO.setFromNickname(user.getNickname());
            messageMO.setFromFace(user.getFace());
        }

        mongoTemplate.save(messageMO);
    }
    // 注入 FansMapper (注意：这里最好注入 Mapper 而不是 FansService，防止两个 Service 互相注入产生循环依赖报错)
    @Autowired
    private FansMapper fansMapper;
    @Override
    public List<MessageMO> queryList(String toUserId, Integer page, Integer pageSize) {
        Query query = new Query(Criteria.where("toUserId").is(toUserId));
        int currentPage = (page != null && page > 0) ? page - 1 : 0;
        int currentSize = (pageSize != null && pageSize > 0) ? pageSize : 10;
        PageRequest pageRequest = PageRequest.of(currentPage, currentSize, Sort.by(Sort.Direction.DESC, "createTime"));
        query.with(pageRequest);

        // 1. 从 MongoDB 查出历史消息列表
        List<MessageMO> list = mongoTemplate.find(query, MessageMO.class);

        // 2. 遍历列表，动态修正“关注消息”的实时互关状态
        for (MessageMO msg : list) {
            // MessageEnum.FOLLOW_YOU.type 就是 1
            if (msg.getMsgType() != null && msg.getMsgType() == 1) {

                // 去 MySQL 查最新实时状态：当前用户 (toUserId) 是否关注了 消息发送者 (fromUserId)
                com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.duli.pojo.Fans> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                qw.eq("fan_id", toUserId).eq("vloger_id", msg.getFromUserId());
                boolean isRealTimeFriend = (fansMapper.selectOne(qw) != null);

                // 获取历史 msgContent，覆盖为最新状态
                Map content = msg.getMsgContent();
                if (content == null) {
                    content = new HashMap();
                }
                content.put("isFriend", isRealTimeFriend);
                msg.setMsgContent(content);
            }
        }

        return list;
    }

    @Override
    public boolean deleteMsg(String msgId, String currentUserId) {
        if (org.apache.commons.lang3.StringUtils.isBlank(currentUserId)) {
            return false;
        }
        // 将归属校验和删除放进同一个数据库操作。
        Query query = new Query(Criteria.where("id").is(msgId)
                .and("toUserId").is(currentUserId));
        return mongoTemplate.remove(query, MessageMO.class).getDeletedCount() > 0;
    }
}
