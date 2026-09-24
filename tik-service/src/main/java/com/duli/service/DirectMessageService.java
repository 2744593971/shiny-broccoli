package com.duli.service;

import com.duli.mo.DirectMessageMO;
import com.duli.mo.PrivateConversationMO;
import com.duli.pojo.Users;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class DirectMessageService {
    @Autowired private MongoTemplate mongo;
    @Autowired private IUsersService users;

    public DirectMessageMO send(String senderId, String recipientId, String content) {
        if (StringUtils.isBlank(recipientId) || recipientId.equals(senderId)) {
            throw new IllegalArgumentException("不能给自己发送私信");
        }
        String body = StringUtils.trimToEmpty(content);
        if (body.isEmpty() || body.length() > 1000) {
            throw new IllegalArgumentException("私信内容须为 1 至 1000 字");
        }
        if (users.getById(recipientId) == null) {
            throw new IllegalArgumentException("接收用户不存在");
        }
        DirectMessageMO message = new DirectMessageMO();
        message.setFromUserId(senderId);
        message.setToUserId(recipientId);
        message.setContent(body);
        message.setCreateTime(new Date());
        mongo.save(message);
        updateConversation(senderId, recipientId, message, false);
        updateConversation(recipientId, senderId, message, true);
        return message;
    }

    private void updateConversation(String owner, String peer, DirectMessageMO message, boolean incoming) {
        String id = owner + ":" + peer;
        Update update = new Update()
                .setOnInsert("ownerId", owner).setOnInsert("peerId", peer)
                .set("lastMessageId", message.getId())
                .set("lastMessage", message.getContent())
                .set("lastMessageTime", message.getCreateTime());
        if (incoming) update.inc("unreadCount", 1);
        else update.setOnInsert("unreadCount", 0);
        mongo.upsert(Query.query(Criteria.where("id").is(id)), update, PrivateConversationMO.class);
    }

    public Map<String, Object> conversations(String ownerId, int page, int size) {
        checkPage(page, size);
        Query query = Query.query(Criteria.where("ownerId").is(ownerId))
                .with(Sort.by(Sort.Direction.DESC, "lastMessageTime"))
                .skip((long) (page - 1) * size).limit(size + 1);
        List<PrivateConversationMO> found = mongo.find(query, PrivateConversationMO.class);
        boolean hasMore = found.size() > size;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(size, found.size()); i++) {
            PrivateConversationMO c = found.get(i);
            Users peer = users.getById(c.getPeerId());
            Map<String, Object> row = new HashMap<>();
            row.put("peerId", c.getPeerId());
            row.put("peerNickname", peer == null ? "已注销用户" : peer.getNickname());
            row.put("peerFace", peer == null ? "" : peer.getFace());
            row.put("lastMessage", c.getLastMessage());
            row.put("lastMessageTime", c.getLastMessageTime());
            row.put("unreadCount", c.getUnreadCount() == null ? 0 : c.getUnreadCount());
            rows.add(row);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("hasMore", hasMore);
        return result;
    }

    public Map<String, Object> messages(String ownerId, String peerId, int page, int size) {
        checkPeer(ownerId, peerId);
        checkPage(page, size);
        Criteria pair = new Criteria().orOperator(
                Criteria.where("fromUserId").is(ownerId).and("toUserId").is(peerId),
                Criteria.where("fromUserId").is(peerId).and("toUserId").is(ownerId));
        Query query = Query.query(pair).with(Sort.by(Sort.Direction.DESC, "createTime", "id"))
                .skip((long) (page - 1) * size).limit(size + 1);
        List<DirectMessageMO> found = mongo.find(query, DirectMessageMO.class);
        boolean hasMore = found.size() > size;
        List<DirectMessageMO> rows = new ArrayList<>(found.subList(0, Math.min(size, found.size())));
        Collections.reverse(rows);
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("hasMore", hasMore);
        return result;
    }

    public void markRead(String ownerId, String peerId) {
        checkPeer(ownerId, peerId);
        Query unread = Query.query(Criteria.where("toUserId").is(ownerId)
                .and("fromUserId").is(peerId).and("readTime").is(null));
        long marked = mongo.updateMulti(unread, new Update().set("readTime", new Date()), DirectMessageMO.class).getModifiedCount();
        if (marked > 0) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(ownerId + ":" + peerId)
                    .and("unreadCount").gte(marked)),
                    new Update().inc("unreadCount", -marked), PrivateConversationMO.class);
        }
    }

    private void checkPeer(String ownerId, String peerId) {
        if (StringUtils.isBlank(peerId) || peerId.equals(ownerId)) {
            throw new IllegalArgumentException("会话用户无效");
        }
    }

    private void checkPage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 50) {
            throw new IllegalArgumentException("分页参数超出范围");
        }
    }
}


