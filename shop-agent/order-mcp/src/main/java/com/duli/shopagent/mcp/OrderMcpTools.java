package com.duli.shopagent.mcp;

import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 边界：这两个方法由 MCP server 注册为远程工具。
 * authToken 属于请求元数据，不在工具 JSON Schema 中，模型无法指定或修改它。
 */
@Component
public class OrderMcpTools {
    private final OrderBackendClient backend;

    public OrderMcpTools(OrderBackendClient backend) {
        this.backend = backend;
    }

    @McpTool(name = "list_my_orders", description = "分页查询当前登录用户自己的商城订单。仅查询，不下单、不支付。")
    public String listMyOrders(
            @McpToolParam(description = "页码，从 1 开始", required = true) int page,
            @McpToolParam(description = "每页数量，1 到 20", required = true) int pageSize,
            McpMeta meta) {
        return backend.list(token(meta), page, pageSize).toString();
    }

    @McpTool(name = "get_my_order", description = "按订单号查询当前登录用户自己的商城订单详情。")
    public String getMyOrder(
            @McpToolParam(description = "订单号", required = true) String orderId,
            McpMeta meta) {
        return backend.detail(token(meta), orderId).toString();
    }

    private String token(McpMeta meta) {
        Object value = meta.get("authToken");
        return value instanceof String ? (String) value : null;
    }
}
