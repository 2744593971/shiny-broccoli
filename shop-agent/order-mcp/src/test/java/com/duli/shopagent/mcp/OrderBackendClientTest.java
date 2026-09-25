package com.duli.shopagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证身份头传递、旧商城业务错误处理和返回给模型的数据白名单。 */
class OrderBackendClientTest {
    private HttpServer server;
    private OrderBackendClient client;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shop/", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("headerUserToken"));
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = new OrderBackendClient("http://127.0.0.1:" + server.getAddress().getPort(),
                new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listForwardsTokenButOnlyReturnsSafeFields() {
        response.set("""
                {"status":200,"data":{"page":1,"hasMore":false,"rows":[
                  {"id":"o1","title":"耳机","amount":99.90,"status":"PAID",
                   "createdAt":123,"receiverName":"张三","receiverPhone":"13800000000",
                   "receiverAddress":"私密地址","paymentChannel":"test"}]}}
                """);
        String result = client.list("valid-jwt", 1, 10).toString();
        assertEquals("valid-jwt", receivedToken.get());
        assertTrue(receivedQuery.get().contains("page=1"));
        assertTrue(result.contains("耳机"));
        assertFalse(result.contains("receiverName"));
        assertFalse(result.contains("13800000000"));
        assertFalse(result.contains("私密地址"));
    }

    @Test
    void legacyBusinessFailureIsNotTreatedAsSuccess() {
        response.set("{\"status\":500,\"msg\":\"登录已过期\",\"data\":null}");
        assertEquals("登录状态已失效，请重新登录",
                assertThrows(IllegalArgumentException.class, () -> client.list("expired", 1, 10))
                        .getMessage());
    }

    @Test
    void detailUsesOnlyOrderIdAndRejectsMissingToken() {
        response.set("{\"status\":200,\"data\":{\"id\":\"o2\",\"status\":\"WAIT_PAY\",\"receiverPhone\":\"secret\"}}");
        assertThrows(IllegalArgumentException.class, () -> client.detail("", "o2"));
        String result = client.detail("valid-jwt", "o2").toString();
        assertTrue(receivedQuery.get().contains("id=o2"));
        assertFalse(receivedQuery.get().contains("userId"));
        assertFalse(result.contains("secret"));
    }
}
