package com.duli.shopagent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 订单 Agent 的 HTTP 入口；与旧商城和 MCP 服务分别启动。 */
@SpringBootApplication
public class AgentApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApiApplication.class, args);
    }
}
