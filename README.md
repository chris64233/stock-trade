# stock-trade

股票交易后台服务，基于 Spring Boot 提供 Web API、参数校验和数据持久化能力。

## 环境

- Java 21
- Spring Boot 3.5.16
- Maven Wrapper
- H2

## 常用命令

运行测试：

    ./mvnw test

启动应用：

    ./mvnw spring-boot:run

## 主要接口

- `POST /api/orders` 创建委托（`clientOrderId` 幂等）
- `GET /api/orders/{id}` 查询委托（含 `filledQuantity`、`remainingQuantity`）
- `POST /api/orders/{id}/cancel` 撤单（`FILLED` 不可撤，重复撤单幂等）
- `POST /api/orders/{id}/amendments` 改单，请求体为 `amendId`、`quantity`、`limitPrice`；
  仅 `OPEN`、`PARTIALLY_FILLED` 可改单，新总数量必须严格大于已成交数量；`amendId` 全局唯一，
  相同字段重复提交返回首次改单结果（200），字段不一致返回 409；每次成功改单写入一条审计记录
- `POST /api/orders/{id}/executions` 登记成交回报，请求体为 `executionId`、`quantity`、`price`；
  `executionId` 全局唯一，相同字段重复提交返回原成交回报（200），字段不一致返回 409
- `GET /api/orders/{id}/execution-summary` 查询指定委托的成交汇总（成交笔数、总成交金额、
  加权平均成交价、最晚成交时间等，金额与均价均保留 4 位小数）
- `POST /api/orders/{id}/executions/reversals` 撤销成交回报，请求体为 `reversalId`、`executionId`；
  `reversalId` 去除首尾空白后不能为空且全局唯一。只有已登记且尚未撤销的成交可以撤销，撤销后从委托
  已成交数量中扣除该笔数量并立即重算剩余数量（`FILLED`/`PARTIALLY_FILLED` 依剩余成交变为
  `OPEN`/`PARTIALLY_FILLED`，`CANCELLED` 保持不变）；成交不存在返回 404，已撤销返回 409。
  相同 `reversalId` 与相同 `executionId` 重复提交返回首次撤销结果（200），不再扣减或新增审计；
  相同 `reversalId` 对应不同成交返回 409。被撤销的成交保留并可在明细中追溯（带 `reversed`、
  `reversedAt`），但不再计入成交汇总；复用原 `executionId` 登记成交返回 409。
  同样支持 `POST /api/executions/reversals`（请求体含 `reversalId`、`executionId`）和
  `POST /api/executions/{executionId}/reversals`（请求体含 `reversalId`）两个入口。
  每次成功撤销在同一事务内写入一条审计记录（撤销标识、成交标识、委托标识、成交数量、成交价格、
  撤销时间）。
