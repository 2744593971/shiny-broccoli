package com.duli.controller;
import com.duli.service.IShopTradeService;
import com.duli.exceptions.*;
import com.duli.interceptor.JwtInterceptor;
import com.duli.utils.JwtUtil;
import com.duli.bo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 支付和管理员接口不能通过匿名请求或前端伪造 userId 绕过已有 JWT。 */
class ShopTradeControllerTest {
    MockMvc mvc;IShopTradeService trade;String token;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        trade=mock(IShopTradeService.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        JwtUtil jwt=new JwtUtil();jwt.setSecretKey("trade-http-test-only");jwt.setExpireTime(60000);
        token=JwtUtil.createToken("buyer","test");
        when(values.get("USER_TOKEN:buyer")).thenReturn(token);
        JwtInterceptor interceptor=new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor,"stringRedisTemplate",redis);
        mvc=MockMvcBuilders.standaloneSetup(new ShopTradeController(trade)).addInterceptors(interceptor)
            .setControllerAdvice(new ShopExceptionHandler(),new GraceExceptionHandler()).build();
    }
    @ParameterizedTest
    @ValueSource(strings={"/shop/payment","/shop/payment/test-confirm","/shop/order/cancel","/shop/order/refund","/shop/order/receive","/shop/admin/ship","/shop/payment/mock-notification","/shop/payment/mock-callback","/shop/admin/payment-events/replay"})
    void anonymousMutationDenied(String path) throws Exception {
        mvc.perform(post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(jsonPath("$.msg").value("请先登录"));
        verifyNoInteractions(trade);
    }
    @ParameterizedTest @ValueSource(strings={"/shop/admin/orders","/shop/trade/config","/shop/payment/detail","/shop/admin/payment-events"})
    void anonymousReadDenied(String path) throws Exception {
        mvc.perform(get(path).servletPath(path)).andExpect(jsonPath("$.msg").value("请先登录"));
        verifyNoInteractions(trade);
    }
    @Test void invalidChannelRejected() throws Exception {
        mvc.perform(post("/shop/payment").servletPath("/shop/payment").header("headerUserToken",token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"order1\",\"channel\":\"FAKE\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(trade);
    }
    @Test void invalidTrackingRejected() throws Exception {
        mvc.perform(post("/shop/admin/ship").servletPath("/shop/admin/ship").header("headerUserToken",token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"order1\",\"carrier\":\"test\",\"trackingNo\":\"<script>\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(trade);
    }
    @Test void actorIsAlwaysFromJwt() throws Exception {
        mvc.perform(post("/shop/order/refund").servletPath("/shop/order/refund").header("headerUserToken",token)
            .param("orderId","order1").param("userId","1001")).andExpect(status().isOk());
        verify(trade).refund("buyer","order1");
    }
    @Test void adminRejectionKeeps403() throws Exception {
        when(trade.adminOrders("buyer",1,12)).thenThrow(new ShopException(403,"没有商城管理权限"));
        mvc.perform(get("/shop/admin/orders").servletPath("/shop/admin/orders").header("headerUserToken",token)
            .param("userId","1001")).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }
    /** 回调体缺少签名和支付字段时由 Bean Validation 拒绝，不进入业务层。 */
    @Test void malformedCallbackRejected() throws Exception {
        mvc.perform(post("/shop/payment/mock-callback").servletPath("/shop/payment/mock-callback")
                .header("headerUserToken",token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trade);
    }

    /** 重放请求的管理员身份只取 JWT，不能通过查询参数冒充。 */
    @Test void replayUsesJwtActor() throws Exception {
        when(trade.replayEvent("buyer","event1")).thenThrow(new ShopException(403,"没有商城管理权限"));
        mvc.perform(post("/shop/admin/payment-events/replay").servletPath("/shop/admin/payment-events/replay")
                .header("headerUserToken",token).param("eventId","event1").param("userId","admin"))
                .andExpect(status().isForbidden());
        verify(trade).replayEvent("buyer","event1");
    }
}
