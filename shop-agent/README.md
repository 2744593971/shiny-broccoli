# 抖音商城订单查询 Agent

本目录有独立的 Boot 3 父 POM，同时已加入根 Maven 聚合，专门处理“根据自然语言查询当前登录用户的商城订单”。它不包含下单、支付、取消、退款等写操作。

## 为什么独立运行

现有商城后端 `../pom.xml` 使用 Spring Boot 2.5、Java 8；本工程使用 Java 17、Spring Boot 3.5.6、Spring AI 1.1.8。两个进程通过旧商城现有的只读 HTTP 接口连接，无需升级原有业务服务。

## 结构与调用流程

```text
用户 / uni-app
  现有商城网关 /agent/orders/query + headerUserToken
    -> POST /agent/orders/query
    -> agent-api (8086) / Spring AI ChatClient
       -> Tool calling：模型只看到 list_my_orders、get_my_order 的名称和参数
          -> Spring AI MCP Client（Streamable HTTP）
             -> order-mcp (127.0.0.1:8087/mcp)
                -> MCP Tool：OrderMcpTools
                   -> OrderBackendClient
                      -> 旧商城 GET /shop/orders 或 GET /shop/order
                         -> JwtInterceptor 校验 JWT + Redis 登录状态
                         -> IShopService 按当前用户 ID 查询
```

| 位置 | 职责 |
| --- | --- |
| `agent-api/.../OrderAgentService.java` | **Tool calling**：`ChatClient.prompt().tools(guarded)` 把两个 MCP 查询工具提供给模型，并检查工具是否真的被调用；模型决定列表页码或订单号。 |
| `agent-api/src/main/resources/application.yml` | **MCP Client**：连接 `order-mcp` 的 `/mcp` Streamable HTTP 端点。 |
| `order-mcp/.../OrderMcpTools.java` | **MCP Server Tool**：`@McpTool` 定义 `list_my_orders`、`get_my_order`。 |
| `order-mcp/src/main/resources/application.yml` | **MCP Server**：只监听本机 127.0.0.1:8087，采用 `STREAMABLE` 协议。 |
| `order-mcp/.../OrderBackendClient.java` | 只调用旧商城两个 GET 接口，并把模型可见字段限制为订单号、标题、金额、状态和时间。 |
| `../../tiktok/pages/shop/agent.vue` | Uni-app 查询输入页，从“我的订单”进入，经现有商城网关访问 Agent。 |

**MCP 与 tool calling 的关系**：MCP 是 Agent 与订单工具服务之间的通信协议；tool calling 是模型提出工具调用、Spring AI 执行并把结果送回模型的过程。本项目的 MCP 工具恰好作为 Spring AI tool calling 的工具来源。

## 身份和数据边界

1. 客户端沿用旧商城的 `headerUserToken` 请求头。Agent **不接收 userId**，也不会把 Token 拼进提示词。
2. `OrderAgentService` 把 Token 放进 `toolContext`。Spring AI 的 MCP 客户端将它传为 MCP 请求的 `_meta.authToken`；`OrderMcpTools` 从 `McpMeta` 取出后透传给旧商城。Token 不属于工具参数 Schema，模型无法改写它。
3. 旧商城 `JwtInterceptor` 校验签名、过期时间和 Redis 中的当前 Token；`ShopController` 从 `currentUserId` 取身份；`ShopServiceImpl` 只查该用户的订单。
4. MCP 工具只返回白名单字段，不把收货姓名、电话、地址或支付信息送入模型。订单标题按数据处理，不接受其中的指令。
5. MCP 端点默认只监听本机。若部署成跨主机服务，应在 MCP 前加传输层认证与 TLS，且只允许可信 Agent 访问；不要直接公开端口 8087。

## 在 IntelliJ IDEA 中运行

根目录 `../pom.xml` 已把 `shop-agent` 加入 Maven 聚合。打开 `D:\tiktok\itiktok` 后，点击右侧 Maven 面板的“重新加载所有 Maven 项目”；随后应看到 `shop-agent`、`order-mcp`、`agent-api`。项目 SDK 与 Maven Runner JRE 使用 **Java 17**，旧模块仍按 Java 8 编译。

项目自带两个共享运行配置（`../.run/`）：

1. 先启动已有的 `tiktok-8077` 旧商城配置。
2. 启动 `Shop Order MCP`。它默认把订单请求转到 `http://127.0.0.1:8077`；若旧商城使用其他端口，修改此配置中的 `SHOP_BACKEND_URL` 环境变量。
3. 在 `Shop Order Agent` 的“编辑配置 → 环境变量”中填入自己的 `OPENAI_API_KEY`，再启动它。使用兼容接口时还可设置 `OPENAI_BASE_URL` 和 `OPENAI_MODEL`。密钥不要提交到 Git。
4. 使用下方本机调用示例验证。手机端页面还需要把现有商城网关的 `/agent/` 转发到 8086。

`OPENAI_API_KEY` 是模型服务的必需凭据；没有它，Agent 会在启动时明确报错。MCP 服务无需模型密钥。命令行也可从根目录运行 `mvn -pl shop-agent/order-mcp,shop-agent/agent-api -am test`。
## 启动

前提：Java 17、Maven、已启动的旧商城后端、一个支持工具调用的 OpenAI 兼容模型及其 API Key。

```powershell
cd D:\tiktok\itiktok\shop-agent
mvn clean package
$env:SHOP_BACKEND_URL = "http://127.0.0.1:8080"  # 改为旧商城实际地址
java -jar order-mcp\target\order-mcp-1.0.0.jar
```

在另一个终端：

```powershell
cd D:\tiktok\itiktok\shop-agent
$env:OPENAI_API_KEY = "你的密钥"
$env:OPENAI_MODEL = "gpt-4o-mini" # 或支持 tool calling 的兼容模型
# 可选：$env:OPENAI_BASE_URL = "https://你的兼容接口根地址"
# 可选：$env:ORDER_MCP_URL = "http://127.0.0.1:8087"
java -jar agent-api\target\agent-api-1.0.0.jar
```

旧商城若由网关暴露在 80 端口，请把 `SHOP_BACKEND_URL` 指向该网关，而非默认的 8080。不要把 API Key 或用户 Token 写入配置文件提交。

## 接入现有商城入口

Uni-app 的 `pages/shop/agent.vue` 沿用 `App.vue` 的 `serverUrl`，即现有商城受信任的入口。把该入口的 `/agent/` 路径反向代理到本机的 Agent 服务；Agent 和 MCP 服务均默认只监听本机。示例 Nginx 片段：

```nginx
location /agent/ {
    proxy_pass http://127.0.0.1:8086;
}
```

代理会沿用请求头，其中包含 `headerUserToken`。生产环境应让商城入口使用 HTTPS。若暂未配置代理，可从本机使用下方命令直接验证 Agent API；手机端页面在代理配置完成后可用。

## 调用示例

```powershell
$headers = @{ headerUserToken = "用户登录后取得的 Token" }
$body = @{ message = "查一下我的订单" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8086/agent/orders/query" -Headers $headers -ContentType "application/json" -Body $body
```

返回形如 `{"answer":"..."}`。也可以问“查询订单号 xxx 的状态”或“看下一页订单”。列表一次最多 20 条；查询某种状态时回答仅基于已查询页，不宣称遍历了全部订单。当前接口无对话历史，多轮“下一页”请在指令里明确页码。

## 验证

```powershell
mvn test
```

测试验证了匿名请求被拒绝、Token 透传、旧商城 HTTP 200 但业务失败时不误判成功，以及敏感字段不会进入模型返回值。

## 相关官方文档

- [Spring AI 1.1 Tool Calling](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
- [Spring AI 1.1 MCP Client](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html)
- [Spring AI 1.1 MCP Server](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html)
