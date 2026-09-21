package com.duli.controller;

import com.duli.service.IShopService;
import com.duli.bo.ShopOrderBO;
import com.duli.exceptions.ShopException;
import com.duli.exceptions.ShopExceptionHandler;

import com.duli.interceptor.JwtInterceptor;
import com.duli.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 层独立验证真实 JWT 拦截器，Redis/业务服务使用测试替身，不访问线上账号。 */
class ShopControllerTest {
    MockMvc mvc;
    IShopService service;
    com.duli.service.ShopOrderSubmissionService submissions;
    com.duli.service.ShopOrderMessageService messages;
    String token;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        service=mock(IShopService.class);
        submissions=mock(com.duli.service.ShopOrderSubmissionService.class);
        messages=mock(com.duli.service.ShopOrderMessageService.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        JwtUtil jwt=new JwtUtil();
        jwt.setSecretKey("shop-http-test-key-only");
        jwt.setExpireTime(60000);
        token=JwtUtil.createToken("buyer","test");
        when(values.get("USER_TOKEN:buyer")).thenReturn(token);
        JwtInterceptor interceptor=new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor,"stringRedisTemplate",redis);
        mvc=MockMvcBuilders.standaloneSetup(new ShopController(service,submissions,messages))
            .addInterceptors(interceptor).setControllerAdvice(new ShopExceptionHandler(),
                new com.duli.exceptions.GraceExceptionHandler()).build();
    }
    @Test void anonymousCanBrowse() throws Exception {
        mvc.perform(get("/shop/products").servletPath("/shop/products")).andExpect(jsonPath("$.status").value(200));
        mvc.perform(get("/shop/seckill").servletPath("/shop/seckill")).andExpect(jsonPath("$.status").value(200));
        mvc.perform(get("/shop/detail").servletPath("/shop/detail").param("productId","p")).andExpect(jsonPath("$.status").value(200));
        verify(service).detail("p",null);
    }
    @Test void anonymousCannotReadOrCreateOrders() throws Exception {
        mvc.perform(get("/shop/orders").servletPath("/shop/orders")).andExpect(jsonPath("$.msg").value("请先登录"));
        mvc.perform(get("/shop/order").servletPath("/shop/order").param("id","x")).andExpect(jsonPath("$.msg").value("请先登录"));
        mvc.perform(post("/shop/order").servletPath("/shop/order").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(jsonPath("$.msg").value("请先登录"));
        verifyNoInteractions(service,submissions,messages);
    }
    @Test void forgedTokenIsRejected() throws Exception {
        mvc.perform(get("/shop/orders").servletPath("/shop/orders").header("headerUserToken","forged"))
            .andExpect(jsonPath("$.status").value(500));
        verifyNoInteractions(service,submissions,messages);
    }
    @Test void actorComesFromTokenNotQueryParameter() throws Exception {
        mvc.perform(post("/shop/order").servletPath("/shop/order").header("headerUserToken",token)
            .param("userId","victim").contentType(MediaType.APPLICATION_JSON)
            .content("{\"productId\":\"p\",\"activityId\":\"a\",\"requestId\":\"request_test_0001\",\"receiverName\":\"tester\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"test address\"}"))
            .andExpect(status().isOk());
        ArgumentCaptor<ShopOrderBO> input=ArgumentCaptor.forClass(ShopOrderBO.class);
        verify(submissions).submit(eq("buyer"),input.capture());
        assertEquals("a",input.getValue().getActivityId());
        mvc.perform(get("/shop/orders").servletPath("/shop/orders").header("headerUserToken",token).param("userId","victim"))
            .andExpect(status().isOk());
        verify(service).orders("buyer",1,12);
    }
    @Test void invalidPayloadIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/shop/order").servletPath("/shop/order").header("headerUserToken",token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"../bad\",\"requestId\":\"short\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service,submissions,messages);
    }
    @Test void businessErrorUsesHttpAndBusinessStatus() throws Exception {
        when(service.order("buyer","foreign")).thenThrow(new ShopException(404,"订单不存在或无权查看"));
        mvc.perform(get("/shop/order").servletPath("/shop/order").header("headerUserToken",token).param("id","foreign"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }
    /** 消息和受理结果必须登录，不能按前端 userId 冒充其他人。 */
    @Test void messagesAndQueueResultUseJwtOwner() throws Exception {
        mvc.perform(get("/shop/messages").servletPath("/shop/messages")).andExpect(jsonPath("$.msg").value("请先登录"));
        mvc.perform(get("/shop/order/result").servletPath("/shop/order/result").param("requestId","request_test_0001"))
                .andExpect(jsonPath("$.msg").value("请先登录"));
        verifyNoInteractions(submissions,messages);
        mvc.perform(get("/shop/messages").servletPath("/shop/messages").header("headerUserToken",token).param("userId","victim"))
                .andExpect(status().isOk());
        verify(messages).list("buyer",1,10);
        mvc.perform(post("/shop/messages/read").servletPath("/shop/messages/read").header("headerUserToken",token)
                .param("id","message1").param("userId","victim")).andExpect(status().isOk());
        verify(messages).read("buyer","message1");
        mvc.perform(get("/shop/order/result").servletPath("/shop/order/result").header("headerUserToken",token)
                .param("requestId","request_test_0001").param("userId","victim")).andExpect(status().isOk());
        verify(submissions).result("buyer","request_test_0001");
    }

    /** 限流保持 HTTP 429，前端保留购买幂等键后可安全重试。 */
    @Test void admissionLimitReturns429() throws Exception {
        when(submissions.submit(eq("buyer"),any())).thenThrow(new ShopException(429,"排队人数较多"));
        mvc.perform(post("/shop/order").servletPath("/shop/order").header("headerUserToken",token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"p\",\"requestId\":\"request_test_0001\",\"receiverName\":\"tester\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"test address\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.status").value(429));
    }
}
