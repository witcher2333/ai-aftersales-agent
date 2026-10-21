# 系统设计与面试讲解

## 请求路径

```text
React 页面
  └─ HTTP Basic → Spring Boot API
       ├─ 订单与售后服务 → MySQL
       ├─ 规则查询 → Redis 缓存 → MySQL 回退
       └─ 售后单创建 → analysis_tasks（与售后单同一数据库事务）
                           └─ 定时工作器 → 模拟分析或模型 API
                                           └─ 只读工具 → MySQL / 规则服务
```

MySQL 是订单、售后状态和分析任务的可信数据源。Redis 只缓存生效的规则，读取失败时直接访问 MySQL。模型输出是客服建议，批准或拒绝由客服在单独的接口中执行。

## 状态图

```text
SUBMITTED → IN_REVIEW → APPROVED → CLOSED
                     ↘ REJECTED → CLOSED
```

`after_sale_cases.version` 每次状态变化递增。处理请求提交 `expectedVersion`，数据库更新同时检查状态和版本；如果已经被别的请求修改，返回 HTTP 409。事件表保留操作者、前后状态和理由。

## 重复请求与并发

- 顾客创建售后单时提交 `idempotencyKey`。`(customer_id,idempotency_key)` 有数据库唯一约束，重放同一请求返回原工单；相同键但不同内容返回冲突。
- 创建时在数据库事务内锁定关联订单行，然后检查该订单是否已有处理中的售后单。这个顺序让同一订单的两个并发申请串行判断。
- 分析任务与售后单在同一事务中创建，因此不会出现“售后单已保存但任务记录丢失”。工作器通过条件更新领取任务；任务中断后可恢复，结果按 `case_id` 更新或插入，允许重试。

## Agent 边界

可用工具固定为 `get_order`、`get_logistics`、`get_policies`、`find_similar_cases`。工具的目标订单由服务端当前工单决定，模型不能指定任意订单 ID。模型最多调用五轮，输出分类、建议和查询依据。模型服务未配置时，使用明确标记的 `demo-rule-engine` 模拟结果。

## 可扩展方向

如果后续需要独立扩展任务处理能力，可以把数据库任务表作为待发布事件的可靠来源，再接入 RabbitMQ；消费者仍需保持幂等。若要连接真实退款服务，需要增加资金操作权限、审批与对账，而不是直接让 Agent 执行。
