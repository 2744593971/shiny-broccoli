package com.duli.mo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.Date;

@Data
@Document("private_conversation")
@CompoundIndex(name = "owner_last_time", def = "{'ownerId': 1, 'lastMessageTime': -1}")
public class PrivateConversationMO {
    @Id private String id;
    private String ownerId;
    private String peerId;
    private String lastMessageId;
    private String lastMessage;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastMessageTime;
    private Integer unreadCount;
}
