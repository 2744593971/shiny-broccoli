package com.duli.repository;

import com.duli.mo.MessageMO;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends MongoRepository<MessageMO, String> {

    /**
     * 这是一个基于 Spring Data 命名规则的查询方法。
     * 相当于 SQL: SELECT * FROM message WHERE to_user_id = ? ORDER BY create_time DESC
     * 
     * @param toUserId 接收方ID
     * @param pageable 分页与排序对象
     * @return 消息列表
     */
    List<MessageMO> findAllByToUserIdOrderByCreateTimeDesc(String toUserId, Pageable pageable);
    
}