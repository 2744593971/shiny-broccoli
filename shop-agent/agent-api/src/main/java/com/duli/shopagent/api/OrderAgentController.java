package com.duli.shopagent.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent API：前端沿用现有 headerUserToken 头。
 * 不接收 userId，订单所属用户最终由旧商城的 JWT 拦截器判定。
 */
@RestController
@RequestMapping("/agent/orders")
public class OrderAgentController {
    private final OrderAgentService agent;

    public OrderAgentController(OrderAgentService agent) {
        this.agent = agent;
    }

    @PostMapping("/query")
    public Answer query(@RequestHeader(value = "headerUserToken", required = false) String token,
                        @Valid @RequestBody Question question) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return new Answer(agent.query(question.message(), token));
    }

    public record Question(@NotBlank @Size(max = 500) String message) {}
    public record Answer(String answer) {}
}
