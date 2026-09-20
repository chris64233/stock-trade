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
- `POST /api/orders/{id}/executions` 登记成交回报，请求体为 `executionId`、`quantity`、`price`；
  `executionId` 全局唯一，相同字段重复提交返回原成交回报（200），字段不一致返回 409
- `GET /api/orders/{id}/executions` 分页查询指定委托的成交明细，支持 `page`（默认 0，不小于 0）
  和 `size`（默认 20，1 到 100）查询参数；按 `executedAt` 升序、`id` 升序排序，
  响应包含委托当前的 `orderStatus`、`filledQuantity`、`remainingQuantity` 及分页信息
