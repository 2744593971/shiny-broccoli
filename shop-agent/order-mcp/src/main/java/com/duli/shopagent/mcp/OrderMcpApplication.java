package com.duli.shopagent.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP 服务入口。单独进程运行，避免把 Spring AI / Boot 3 依赖带入旧商城。
 */
@SpringBootApplication
public class OrderMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderMcpApplication.class, args);
    }
}
