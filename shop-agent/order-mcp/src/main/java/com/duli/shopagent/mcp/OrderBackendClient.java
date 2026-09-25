package com.duli.shopagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 只读适配器：只允许请求旧商城的两个订单 GET 接口。
 * 用户身份只能来自 MCP 元数据中的登录 Token，不能由模型填写 userId。
 */
@Component
public class OrderBackendClient {
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public OrderBackendClient(@Value("${shop.backend-base-url}") String baseUrl, ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }

    /** 分页查询本人订单。后端 JWT 拦截器负责验证 Token 与 Redis 登录状态。 */
    public ObjectNode list(String token, int page, int pageSize) {
        requireToken(token);
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 20) {
            throw new IllegalArgumentException("页码需为 1-10000，每页需为 1-20 条");
        }
        JsonNode data = call(token, "/shop/orders", "page", page, "pageSize", pageSize);
        if (!data.isObject() || !data.path("rows").isArray()) {
            throw new IllegalStateException("商城订单响应格式异常");
        }
        ObjectNode safe = mapper.createObjectNode();
        safe.put("page", data.path("page").asInt(page));
        safe.put("hasMore", data.path("hasMore").asBoolean(false));
        ArrayNode rows = safe.putArray("rows");
        // 白名单投影：姓名、电话、地址、支付信息等不送入模型。
        for (JsonNode order : data.path("rows")) {
            rows.add(safeOrder(order));
        }
        return safe;
    }

    /** 使用后端按 userId + orderId 的查询，保证不能通过猜订单号看别人的订单。 */
    public ObjectNode detail(String token, String orderId) {
        requireToken(token);
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("订单号格式无效");
        }
        JsonNode data = call(token, "/shop/order", "id", orderId, null, null);
        if (!data.isObject()) {
            throw new IllegalStateException("商城订单响应格式异常");
        }
        return safeOrder(data);
    }

    private JsonNode call(String token, String path, String key1, Object value1, String key2, Object value2) {
        try {
            JsonNode response = restClient.get()
                    .uri(builder -> {
                        builder.path(path).queryParam(key1, value1);
                        if (key2 != null) builder.queryParam(key2, value2);
                        return builder.build();
                    })
                    .header("headerUserToken", token)
                    .retrieve().body(JsonNode.class);
            if (response == null) throw new IllegalStateException("商城无响应");
            if (response.path("status").asInt(-1) != 200) {
                // 旧拦截器在 Token 无效时也可能返回 HTTP 200，必须检查业务 status。
                String message = response.path("msg").asText("");
                if (message.contains("登录") || message.contains("Token") || message.contains("下线")) {
                    throw new IllegalArgumentException("登录状态已失效，请重新登录");
                }
                if (message.contains("订单不存在") || message.contains("无权查看")) {
                    throw new IllegalArgumentException("订单不存在或无权查看");
                }
                throw new IllegalStateException("商城查询订单失败");
            }
            return response.path("data");
        } catch (RestClientException exception) {
            // 不把响应原文或带 Token 的请求信息暴露给模型/客户端。
            throw new IllegalStateException("商城服务暂不可用");
        }
    }

    static void requireToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("请先登录");
    }

    private ObjectNode safeOrder(JsonNode order) {
        ObjectNode safe = mapper.createObjectNode();
        copy(order, safe, "id");
        copy(order, safe, "title");
        copy(order, safe, "amount");
        copy(order, safe, "status");
        copy(order, safe, "createdAt");
        copy(order, safe, "expiresAt");
        copy(order, safe, "paidAt");
        copy(order, safe, "shippedAt");
        copy(order, safe, "receivedAt");
        return safe;
    }

    private void copy(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) target.set(field, value);
    }
}
