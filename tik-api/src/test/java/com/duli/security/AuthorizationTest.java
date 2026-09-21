package com.duli.security;

import com.duli.bo.CommentBO;
import com.duli.bo.VlogBO;
import com.duli.controller.*;
import com.duli.interceptor.JwtInterceptor;
import com.duli.mo.MessageMO;
import com.duli.pojo.Comment;
import com.duli.pojo.Vlog;
import com.duli.pojo.Users;
import com.duli.mapper.FansMapper;
import com.duli.service.*;
import com.duli.service.impl.MsgServiceImpl;
import com.duli.utils.JwtUtil;
import com.duli.vo.IndexVlogVO;
import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real JWT verification + Redis session checks + MVC parameter binding, without live services. */
class AuthorizationTest {
    private MockMvc mvc;
    private IVlogService vlogs;
    private ICommentService comments;
    private IFansService fans;
    private MsgService messages;
    private IUsersService users;
    private ValueOperations<String, String> tokens;
    private String token;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        vlogs = mock(IVlogService.class);
        comments = mock(ICommentService.class);
        fans = mock(IFansService.class);
        messages = mock(MsgService.class);
        users = mock(IUsersService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        tokens = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(tokens);
        JwtUtil jwt = new JwtUtil();
        jwt.setSecretKey("authorization-regression-test-only-key");
        jwt.setExpireTime(60000);
        token = JwtUtil.createToken("A", "test");
        when(tokens.get("USER_TOKEN:A")).thenReturn(token);

        JwtInterceptor interceptor = new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", redis);
        VideoAccess access = new VideoAccess();
        ReflectionTestUtils.setField(access, "vlogService", vlogs);
        VlogController vc = new VlogController();
        ReflectionTestUtils.setField(vc, "vlogService", vlogs);
        ReflectionTestUtils.setField(vc, "videoAccess", access);
        CommentController cc = new CommentController();
        ReflectionTestUtils.setField(cc, "commentService", comments);
        ReflectionTestUtils.setField(cc, "videoAccess", access);
        FansController fc = new FansController();
        ReflectionTestUtils.setField(fc, "fansService", fans);
        MsgController mc = new MsgController();
        ReflectionTestUtils.setField(mc, "msgService", messages);
        UserInfoController uc = new UserInfoController();
        ReflectionTestUtils.setField(uc, "userService", users);
        ReflectionTestUtils.setField(uc, "fansMapper", mock(FansMapper.class));
        ReflectionTestUtils.setField(uc, "redisTemplate", redis);
        mvc = MockMvcBuilders.standaloneSetup(vc, cc, fc, mc, uc)
                .setControllerAdvice(new AccessExceptionHandler())
                .addInterceptors(interceptor).build();
        when(vlogs.getById("video")).thenReturn(new Vlog()
                .setId("video").setVlogerId("B").setIsPrivate(0));
        when(comments.getById("comment")).thenReturn(new Comment()
                .setId("comment").setCommentUserId("B").setVlogId("video"));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, String path) {
        return request.servletPath(path).header("headerUserToken", token).header("headerUserId", "B");
    }

    @Test
    void forgedPublisherIsReplaced() throws Exception {
        mvc.perform(authenticated(post("/vlog/publish"), "/vlog/publish")
                .contentType(MediaType.APPLICATION_JSON).content("{\"vlogerId\":\"B\",\"url\":\"video-url\"}"))
                .andExpect(jsonPath("$.status").value(200));
        ArgumentCaptor<VlogBO> bo = ArgumentCaptor.forClass(VlogBO.class);
        verify(vlogs).createVlog(bo.capture());
        assertEquals("A", bo.getValue().getVlogerId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"like", "unlike"})
    void videoLikesUseAuthenticatedActorAndDatabaseAuthor(String action) throws Exception {
        String path = "/vlog/" + action;
        mvc.perform(authenticated(post(path), path).param("userId", "B")
                .param("vlogerId", "forged-author").param("vlogId", "video"))
                .andExpect(jsonPath("$.status").value(200));
        if ("like".equals(action)) verify(vlogs).userLikeVlog("A", "video", "B");
        else verify(vlogs).userUnLikeVlog("A", "video", "B");
    }

    @ParameterizedTest
    @ValueSource(strings = {"changeToPrivate", "changeToPublic"})
    void cannotChangeOtherUsersVideo(String action) throws Exception {
        String path = "/vlog/" + action;
        mvc.perform(authenticated(post(path), path).param("userId", "B").param("vlogId", "video"))
                .andExpect(status().isForbidden());
        verify(vlogs, never()).changeToPrivateOrPublic(anyString(), anyString(), anyInt());
    }

    @Test
    void ownerCanChangeVisibility() throws Exception {
        when(vlogs.getById("video")).thenReturn(new Vlog().setVlogerId("A").setIsPrivate(1));
        mvc.perform(authenticated(post("/vlog/changeToPublic"), "/vlog/changeToPublic").param("vlogId", "video"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).changeToPrivateOrPublic("A", "video", 0);
    }

    @Test
    void cannotListOthersPrivateVideos() throws Exception {
        mvc.perform(authenticated(get("/vlog/myPrivateList"), "/vlog/myPrivateList").param("userId", "B"))
                .andExpect(jsonPath("$.success").value(false));
        verify(vlogs, never()).getMyVlogList(any(), any(), any(), any(), any());
    }

    @Test
    void ownerCanListPrivateVideos() throws Exception {
        mvc.perform(authenticated(get("/vlog/myPrivateList"), "/vlog/myPrivateList").param("userId", "A"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).getMyVlogList("A", "A", 1, 1, 10);
    }

    @Test
    void publicProfileAndLikedListKeepTargetSeparateFromViewer() throws Exception {
        mvc.perform(authenticated(get("/vlog/myPublicList"), "/vlog/myPublicList").param("userId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        mvc.perform(authenticated(get("/vlog/myLikedList"), "/vlog/myLikedList").param("userId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).getMyVlogList("B", "A", 0, 1, 10);
        verify(vlogs).getMyLikedList("B", "A", 1, 10);
    }

    @Test
    void publicProfileHidesContactDetailsButOwnProfileKeepsThem() throws Exception {
        when(users.getById("B")).thenReturn(new Users().setId("B").setNickname("Bob")
                .setMobile("test-mobile").setEmail("test@example.invalid"));
        when(users.getById("A")).thenReturn(new Users().setId("A").setNickname("Alice")
                .setMobile("own-mobile").setEmail("own@example.invalid"));
        mvc.perform(authenticated(get("/userInfo/query"), "/userInfo/query").param("userId", "B"))
                .andExpect(jsonPath("$.data.nickname").value("Bob"))
                .andExpect(jsonPath("$.data.mobile").isEmpty())
                .andExpect(jsonPath("$.data.email").isEmpty());
        mvc.perform(authenticated(get("/userInfo/query"), "/userInfo/query"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.mobile").value("own-mobile"))
                .andExpect(jsonPath("$.data.email").value("own@example.invalid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"followList", "friendList"})
    void personalFeedsUseTokenIdentity(String action) throws Exception {
        String path = "/vlog/" + action;
        mvc.perform(authenticated(get(path), path).param("myId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        if ("followList".equals(action)) verify(vlogs).getMyFollowVlogList("A", 1, 10);
        else verify(vlogs).getMyFriendVlogList("A", 1, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/vlog/detail", "/comment/list", "/comment/counts"})
    void privateVideoAndCommentsAreHiddenFromOtherUsers(String path) throws Exception {
        when(vlogs.getById("video")).thenReturn(new Vlog().setVlogerId("B").setIsPrivate(1));
        mvc.perform(authenticated(get(path), path).param("vlogId", "video").param("userId", "B"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void ownerCanReadPrivateDetail() throws Exception {
        when(vlogs.getById("video")).thenReturn(new Vlog().setVlogerId("A").setIsPrivate(1));
        when(vlogs.getVlogDetailById("A", "video")).thenReturn(new IndexVlogVO());
        mvc.perform(authenticated(get("/vlog/detail"), "/vlog/detail").param("vlogId", "video"))
                .andExpect(jsonPath("$.status").value(200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"follow", "cancel", "queryDoIFollowVloger", "queryMyFollows", "queryMyFans"})
    void fanEndpointsIgnoreForgedActor(String action) throws Exception {
        String path = "/fans/" + action;
        MockHttpServletRequestBuilder request = action.startsWith("query") ? get(path) : post(path);
        mvc.perform(authenticated(request, path).param("myId", "B").param("vlogerId", "C"))
                .andExpect(jsonPath("$.status").value(200));
        switch (action) {
            case "follow": verify(fans).doFollow("A", "C"); break;
            case "cancel": verify(fans).doCancel("A", "C"); break;
            case "queryDoIFollowVloger": verify(fans).queryDoIFollowVloger("A", "C"); break;
            case "queryMyFollows": verify(fans).queryMyFollows("A", 1, 10); break;
            default: verify(fans).queryMyFans("A", 1, 10);
        }
    }

    @Test
    void commentIdentityAndRecipientComeFromTrustedSources() throws Exception {
        mvc.perform(authenticated(post("/comment/create"), "/comment/create")
                .contentType(MediaType.APPLICATION_JSON).content("{\"vlogId\":\"video\",\"commentUserId\":\"B\",\"vlogerId\":\"C\",\"content\":\"hello\",\"fatherCommentId\":\"0\"}"))
                .andExpect(jsonPath("$.status").value(200));
        ArgumentCaptor<CommentBO> bo = ArgumentCaptor.forClass(CommentBO.class);
        verify(comments).createComment(bo.capture());
        assertEquals("A", bo.getValue().getCommentUserId());
        assertEquals("B", bo.getValue().getVlogerId());
    }

    @Test
    void cannotReplyAcrossVideos() throws Exception {
        when(comments.getById("parent")).thenReturn(new Comment().setVlogId("other-video"));
        mvc.perform(authenticated(post("/comment/create"), "/comment/create")
                .contentType(MediaType.APPLICATION_JSON).content("{\"vlogId\":\"video\",\"fatherCommentId\":\"parent\"}"))
                .andExpect(jsonPath("$.success").value(false));
        verify(comments, never()).createComment(any());
    }

    @Test
    void commentDeletionChecksActualOwner() throws Exception {
        mvc.perform(authenticated(delete("/comment/delete"), "/comment/delete")
                .param("commentUserId", "B").param("commentId", "comment").param("vlogId", "video"))
                .andExpect(jsonPath("$.success").value(false));
        verify(comments, never()).deleteComment(any(), any(), any());
        when(comments.getById("comment")).thenReturn(new Comment().setCommentUserId("A").setVlogId("video"));
        mvc.perform(authenticated(delete("/comment/delete"), "/comment/delete")
                .param("commentId", "comment").param("vlogId", "video"))
                .andExpect(jsonPath("$.status").value(200));
        verify(comments).deleteComment("A", "comment", "video");
    }

    @ParameterizedTest
    @ValueSource(strings = {"like", "unlike"})
    void commentLikesUseTokenIdentity(String action) throws Exception {
        String path = "/comment/" + action;
        mvc.perform(authenticated(post(path), path).param("userId", "B").param("commentId", "comment"))
                .andExpect(jsonPath("$.status").value(200));
        if ("like".equals(action)) verify(comments).likeComment("A", "comment");
        else verify(comments).unlikeComment("A", "comment");
    }

    @Test
    void messagesUseAuthenticatedRecipient() throws Exception {
        mvc.perform(authenticated(get("/msg/list"), "/msg/list").param("userId", "B").param("page", "1").param("pageSize", "10"))
                .andExpect(jsonPath("$.status").value(200));
        verify(messages).queryList("A", 1, 10);
        mvc.perform(authenticated(post("/msg/delete"), "/msg/delete").param("msgId", "B-message"))
                .andExpect(jsonPath("$.success").value(false));
        verify(messages).deleteMsg("B-message", "A");
        when(messages.deleteMsg("A-message", "A")).thenReturn(true);
        mvc.perform(authenticated(post("/msg/delete"), "/msg/delete").param("msgId", "A-message"))
                .andExpect(jsonPath("$.status").value(200));
    }

    @Test
    void mongoDeleteIncludesRecipientInAtomicPredicate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MsgServiceImpl service = new MsgServiceImpl();
        ReflectionTestUtils.setField(service, "mongoTemplate", mongo);
        when(mongo.remove(any(Query.class), eq(MessageMO.class))).thenReturn(DeleteResult.acknowledged(0));
        assertFalse(service.deleteMsg("B-message", "A"));
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(query.capture(), eq(MessageMO.class));
        assertEquals("A", query.getValue().getQueryObject().get("toUserId"));
        assertEquals("B-message", query.getValue().getQueryObject().get("id"));
    }

    @Test
    void publicFeedIgnoresForgedUserButUsesValidToken() throws Exception {
        mvc.perform(get("/vlog/indexList").servletPath("/vlog/indexList").param("userId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).getIndexVlogList(null, "", 1, 10);
        mvc.perform(authenticated(get("/vlog/indexList"), "/vlog/indexList").param("userId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).getIndexVlogList("A", "", 1, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "tampered", "expired", "revoked", "replaced"})
    void protectedEndpointsRejectInvalidSessions(String kind) throws Exception {
        String supplied = token;
        if ("tampered".equals(kind)) supplied = "invalid.jwt.token";
        if ("expired".equals(kind)) {
            new JwtUtil().setExpireTime(-60000);
            supplied = JwtUtil.createToken("A", "test");
        }
        if ("revoked".equals(kind)) when(tokens.get("USER_TOKEN:A")).thenReturn(null);
        if ("replaced".equals(kind)) when(tokens.get("USER_TOKEN:A")).thenReturn("new-session");
        MockHttpServletRequestBuilder request = post("/fans/follow").servletPath("/fans/follow")
                .param("myId", "B").param("vlogerId", "C");
        if (!"missing".equals(kind)) request.header("headerUserToken", supplied);
        mvc.perform(request).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(fans);
    }

    @Test
    void invalidTokenOnPublicFeedFallsBackToGuest() throws Exception {
        mvc.perform(get("/vlog/indexList").servletPath("/vlog/indexList")
                .header("headerUserToken", "invalid").param("userId", "B"))
                .andExpect(jsonPath("$.status").value(200));
        verify(vlogs).getIndexVlogList(null, "", 1, 10);
    }
}
