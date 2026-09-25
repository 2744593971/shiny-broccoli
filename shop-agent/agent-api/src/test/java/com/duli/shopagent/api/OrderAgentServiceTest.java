package com.duli.shopagent.api;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 MCP ToolCallback 能按回调注册；曾误用 tools() 导致每次查询都返回 HTTP 500。 */
class OrderAgentServiceTest {
    @Test
    void registersMcpCallbacksForAQuery() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("未查询工具")))));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("list_my_orders").description("查询当前用户订单")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] { callback });

        OrderAgentService service = new OrderAgentService(ChatClient.builder(model), provider);
        // 模型没有真正调用工具时仍返回安全兜底；重点是回调注册不能抛出异常。
        assertEquals("这里只支持查询当前账号的订单，请提供订单查询指令。",
                service.query("查询我的订单", "test-token"));
    }
}