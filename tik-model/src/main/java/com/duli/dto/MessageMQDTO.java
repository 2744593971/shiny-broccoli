package com.duli.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MessageMQDTO implements Serializable {
    private String fromUserId;
    private String toUserId;
    private Integer msgType; // 对应 MessageEnum 的 type
    private Map<String, Object> msgContent;
}