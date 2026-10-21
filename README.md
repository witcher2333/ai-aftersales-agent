# AI 辅助订单售后与异常处理平台

一个可运行的 Java 后端作品：顾客提交售后申请，客服按状态处理，AI 查询订单、物流、规则与历史案例后提供建议。业务规则和最终决定由后端及客服控制。

![客服工作台演示](docs/demo-workspace.jpg)

## 功能

- 订单查询、角色权限、售后申请与处理记录
- 订单级串行校验、请求幂等键、工单版本校验
- 可恢复的异步 AI 分析任务，最多自动尝试三次，客服可手动重试
- 只读工具调用：订单、物流、规则、相似案例
- Redis 规则缓存；不可用时继续读取 MySQL
- React 客服台、Docker Compose 部署

技术栈：Java 21、Spring Boot、Spring Security、JDBC、Flyway、MySQL、Redis、React、Docker。初版异步任务由数据库任务表与定时工作器实现，不依赖消息队列。

## 快速运行

需要 Docker Compose：

```bash
cp .env.example .env
docker compose up --build
```

打开 `http://localhost:8088`。Compose 默认只绑定本机回环地址。演示账号：`customer / customer123` 与 `agent / agent123`；修改 `.env` 中的密码后，首次初始化数据库时会使用新密码。已有数据卷不会自动重置账号。

也可以只启动后端：设置好 Maven 和 Java 21 后运行 `mvn spring-boot:run`。默认使用内存 H2 数据库和模拟 AI 分析，重启后演示数据会重建。前端在 `web/` 下运行 `npm install && npm run dev`，访问 `http://localhost:5173`。

## 体验流程

1. 用顾客账号进入，选择已签收的耳机订单，提交退货申请。
2. 等待几秒后，查看 AI 建议与查询依据。
3. 切换客服账号，领取工单，填写处理理由，批准或拒绝，然后关闭。
4. 用运输中的键盘订单提交物流异常申请，比较两种业务场景。

## 接入真实模型

默认使用确定性的模拟分析，使项目在没有 API 密钥时仍可运行。设置 `AI_ENDPOINT` 为兼容 OpenAI Chat Completions 的完整 URL，并设置 `AI_MODEL`、`AI_API_KEY` 后，分析任务会尝试调用真实模型。Agent 最多执行五轮，开放的工具仅能查询当前工单关联的数据；模型返回的内容仍是建议，不会改变工单状态。不同模型服务对工具调用格式的支持可能不同，应先用一条演示工单验证。

## 关键接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/me` | 当前身份 |
| GET | `/api/orders` | 可见订单 |
| GET | `/api/cases` | 可见售后单 |
| POST | `/api/cases` | 创建售后单，提交 `idempotencyKey` |
| GET | `/api/cases/{id}` | 工单、订单、记录和分析结果 |
| POST | `/api/cases/{id}/take` | 客服领取 |
| POST | `/api/cases/{id}/decision` | 客服批准/拒绝，提交 `expectedVersion` |
| POST | `/api/cases/{id}/close` | 关闭工单 |
| POST | `/api/cases/{id}/analysis/retry` | 重试失败的分析 |

接口通过 HTTP Basic 认证；演示前端把凭据保存在当前浏览器会话中。真实生产环境应改为 HTTPS、正式身份系统与更细的权限治理。

## 测试与项目边界

运行 `mvn test`。测试覆盖售后申请、幂等键、状态流转、权限、分析任务和模型工具调用协议。演示订单及物流数据由程序初始化；批准申请表示客服作出业务决定，不连接真实支付或退款服务。性能数字只有在实际压测后才会写入项目说明。

设计取舍与状态图见 [系统设计](docs/architecture.md)。
