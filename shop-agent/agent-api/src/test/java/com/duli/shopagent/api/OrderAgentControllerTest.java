package com.duli.shopagent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 层不能接受匿名订单查询，也不会把用户 ID 作为请求参数。 */
class OrderAgentControllerTest {
    private final OrderAgentService agent = mock(OrderAgentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderAgentController(agent)).build();

    @Test
    void rejectsMissingTokenBeforeCallingModel() throws Exception {
        mvc.perform(post("/agent/orders/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"查询我的订单\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(agent);
    }

    @Test
    void forwardsOnlyInstructionAndAuthenticatedHeader() throws Exception {
        when(agent.query("查询我的订单", "jwt")).thenReturn("找到一笔订单");
        mvc.perform(post("/agent/orders/query")
                .header("headerUserToken", "jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"查询我的订单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("找到一笔订单"));
        verify(agent).query("查询我的订单", "jwt");
    }
}
