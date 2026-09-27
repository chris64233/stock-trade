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
- `POST /api/orders/{id}/executions/settlements` 结算确认成交回报，请求体为 `settlementId`、`executionId`；
  `settlementId` 去除首尾空白后不能为空且全局唯一。只有已登记、未撤销且未结算的成交可以结算；
  结算与撤销互斥，并发竞争时只有一个成功，失败方返回 409 且不留下状态变更或审计记录。
  结算只确认该笔成交，不改变所属委托的已成交数量、剩余数量和状态；成交状态与结算审计在同一事务写入。
  相同 `settlementId` 与相同 `executionId` 重复提交返回首次结算结果（200），不再新增审计；
  相同 `settlementId` 对应不同成交返回 409（`SETTLEMENT_ID_CONFLICT`）；已结算返回 409
  （`EXECUTION_ALREADY_SETTLED`），已撤销返回 409（`EXECUTION_ALREADY_REVERSED`），成交不存在返回 404。
  已结算的成交不能直接撤销成交回报（返回 409 `EXECUTION_ALREADY_SETTLED`）；需先按下文
  「结算撤销与重新结算」撤销结算，成交回报恢复为未结算后才能撤销成交。
  同样支持 `POST /api/executions/settlements`（请求体含 `settlementId`、`executionId`）和
  `POST /api/executions/{executionId}/settlements`（请求体含 `settlementId`）两个入口。
  每次成功结算写入一条审计记录（结算标识、成交标识、委托标识、成交数量、成交价格、结算时间、
  委托状态与成交数量快照）；成交明细中带 `settled`、`settledAt`、`settlementId` 可追溯。

### 资金账户

- `GET /api/fund-accounts/{accountId}` 查询资金账户余额。账户随委托创建自动初始化（初始余额 0），
  每次结算按成交金额（`price * quantity`）落一笔资金变动：卖出为正向、买入为反向；
  结算撤销追加反向变动把余额恢复。每笔变动都带符号变动额（`signedDelta`）与变动后余额
  （`balanceAfter`），资金账户余额与全部变动记录可完整对账。

### 结算撤销与重新结算

**撤销入口**（三选一，均在同一事务内完成资金回退、撤销审计和成交结算状态恢复）：

- `POST /api/executions/settlements/reversals`（全局入口），请求体：
  `reversalId`、`settlementId`、`reason`
- `POST /api/executions/settlements/{settlementId}/reversals`（按结算号入口），请求体：
  `reversalId`、`reason`
- `POST /api/orders/{id}/executions/{executionId}/settlement-reversal`（按委托+成交入口，
  撤销该成交当前生效的结算），请求体：`reversalId`、`reason`

规则：

- 撤销是**追加反向记录**：保留原结算审计（置 `reversed=true`、`reversedAt`、`reversalId`），
  追加一条结算撤销审计和一笔反向资金变动恢复资金状态；不删除原结算，也不再次改变委托的
  已成交数量、剩余数量和状态；成交回报回到未结算（但不标记为成交撤销）。
- `reversalId` 全局唯一并保证幂等：相同 `reversalId` 以相同 `settlementId`、相同 `reason`
  重复提交返回首次撤销结果（200），不新增审计或资金变动；同号对应不同结算或**不同原因**返回
  409（`REVERSAL_ID_CONFLICT`）。
- 每笔结算只能撤销一次，换号再次撤销同一结算返回 409（`SETTLEMENT_ALREADY_REVERSED`）；
  结算不存在返回 404（`SETTLEMENT_NOT_FOUND`）。
- 结算确认、重新结算与结算撤销共用委托行悲观锁互斥，并发竞争只能形成一个终态；
  资金账户行随后加悲观写锁串行化余额更新，约束失败时整个事务完整回滚。

**重新结算入口**：撤销成功后，对同一成交使用**新的结算号**再次调用普通结算接口即可：

- `POST /api/orders/{id}/executions/settlements`（请求体含 `settlementId`、`executionId`），或
- `POST /api/executions/settlements`、`POST /api/executions/{executionId}/settlements`

重新结算写入全新结算审计与资金变动，响应和审计中的 `replacesSettlementId` 回指被撤销的原结算；
原结算号不能复用（返回 409 `SETTLEMENT_ALREADY_REVERSED`）。撤销—重新结算可以反复进行，
每次都会在链上追加节点。

**变化链查询**：`GET /api/executions/{executionId}/settlement-chain`
（或 `GET /api/orders/{id}/executions/{executionId}/settlement-chain`），按时间返回
`SETTLEMENT` / `REVERSAL` 节点（含各节点的带符号资金变动、变动后余额、撤销原因、
`replacesSettlementId`），并给出当前是否结算（`settled`）与当前生效结算号
（`currentSettlementId`）；原结算、撤销、新结算由此组成不可变关系链，可还原整条资金变化。
