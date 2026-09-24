package com.duli.mo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.Date;

@Data
@Document("direct_message")
@CompoundIndexes({
    @CompoundIndex(name = "dm_from_to_time", def = "{'fromUserId': 1, 'toUserId': 1, 'createTime': -1}"),
    @CompoundIndex(name = "dm_to_from_time", def = "{'toUserId': 1, 'fromUserId': 1, 'createTime': -1}")
})
public class DirectMessageMO {
    @Id private String id;
    private String fromUserId;
    private String toUserId;
    private String content;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date readTime;
}
