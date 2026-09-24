package com.duli.service;

import com.duli.mo.DirectMessageMO;
import com.duli.mo.PrivateConversationMO;
import com.duli.pojo.Users;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DirectMessageServiceTest {
    @Mock MongoTemplate mongo;
    @Mock IUsersService users;
    @InjectMocks DirectMessageService service;

    @Test
    void rejectsSelfAndUnknownRecipientWithoutSaving() {
        assertThrows(IllegalArgumentException.class, () -> service.send("u1", "u1", "hello"));
        assertThrows(IllegalArgumentException.class, () -> service.send("u1", "u2", " "));
        assertThrows(IllegalArgumentException.class, () -> service.send("u1", "u2", "x"));
        verify(mongo, never()).save(any(DirectMessageMO.class));
    }

    @Test
    void sendsToBothConversationsAndIncrementsOnlyRecipientUnread() {
        when(users.getById("u2")).thenReturn(new Users().setId("u2"));
        when(mongo.save(any(DirectMessageMO.class))).thenAnswer(invocation -> {
            DirectMessageMO message = invocation.getArgument(0);
            message.setId("m1");
            return message;
        });
        DirectMessageMO message = service.send("u1", "u2", " hello ");
        assertEquals("hello", message.getContent());
        assertEquals("u1", message.getFromUserId());
        assertEquals("u2", message.getToUserId());

        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).upsert(any(Query.class), updates.capture(), eq(PrivateConversationMO.class));
        Document sender = updates.getAllValues().get(0).getUpdateObject();
        Document recipient = updates.getAllValues().get(1).getUpdateObject();
        assertNull(sender.get("$inc"));
        assertEquals(0, ((Document) sender.get("$setOnInsert")).get("unreadCount"));
        assertEquals(1, ((Document) recipient.get("$inc")).get("unreadCount"));
        assertFalse(((Document) recipient.get("$setOnInsert")).containsKey("unreadCount"));
    }
}
