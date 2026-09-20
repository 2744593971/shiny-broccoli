package com.duli.service;

import com.duli.enums.MessageEnum;
import com.duli.mo.MessageMO;

import java.util.List;
import java.util.Map;

public interface MsgService {

    /**
     * 创建消息 (强制使用 MessageEnum 保证类型安全)
     */
    public void createMsg(String fromUserId,
                          String toUserId,
                          MessageEnum msgEnum,
                          Map<String, Object> msgContent);

    /**
     * 查询消息列表
     */
    public List<MessageMO> queryList(String toUserId,
                                     Integer page,
                                     Integer pageSize);

    /**
     * 删除消息
     */
    public void deleteMsg(String msgId);
}