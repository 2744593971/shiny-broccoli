package com.duli.pojo;
import lombok.Data;
/** ==================== Codex 优化：投递记录与租约 ==================== */
@Data
public class MessageOutbox {
    private String id;
    private String routingKey;
    private String payload;
    private String leaseToken;
    private int attempts;
}
