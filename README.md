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
- `POST /api/executions/revocations` 撤销成交回报，请求体为 `revokeId`、`executionId`；
  `revokeId` 去除首尾空白后不能为空且全局唯一；仅已登记且未撤销的成交可以撤销，成交不存在返回
  404，已被其他撤销请求处理返回 409。撤销成功后从所属委托的已成交数量中扣减并重算剩余数量，
  `FILLED`/`PARTIALLY_FILLED` 委托回退为 `OPEN`/`PARTIALLY_FILLED`，已 `CANCELLED` 的委托保持
  `CANCELLED`；相同 `revokeId` 与相同成交重复提交返回首次撤销结果（200），相同 `revokeId` 对应
  不同成交返回 409。被撤销的成交保留可追溯但不计入成交汇总，明细中带有 `revoked`、`revokeId`、
  `revokedAt` 标记，且原 `executionId` 不能再次登记成交
