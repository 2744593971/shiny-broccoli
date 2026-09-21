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
    // Codex 优化：固定为改造前类的 serialVersionUID，兼容队列中已有Java序列化消息。
    private static final long serialVersionUID = -2766353397371349583L;
    // ==================== Codex 优化：稳定事件ID预留消费去重，发生时间不因重试改变 ====================
    private String eventId;
    private Long occurredAt;
    private String fromUserId;
    private String toUserId;
    private Integer msgType; // 对应 MessageEnum 的 type
    private Map<String, Object> msgContent;
}
