package com.example.stocktrade.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExecutionSettlementController {

    private final OrderService orderService;

    public ExecutionSettlementController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/executions/settlements")
    public ResponseEntity<ExecutionSettlementResponse> settle(
            @Valid @RequestBody SettleExecutionRequest request) {
        OrderService.SettleExecutionResult result =
                orderService.settleExecution(null, request.settlementId(), request.executionId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ExecutionSettlementResponse.of(result.settlement(), result.movement()));
    }

    @PostMapping("/api/executions/{executionId}/settlements")
    public ResponseEntity<ExecutionSettlementResponse> settleByExecutionId(
            @PathVariable String executionId,
            @Valid @RequestBody ExecutionSettlementRequest request) {
        OrderService.SettleExecutionResult result =
                orderService.settleExecution(null, request.settlementId(), executionId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ExecutionSettlementResponse.of(result.settlement(), result.movement()));
    }

    /**
     * 结算撤销（全局入口）：请求体含 reversalId、settlementId、reason。
     * 撤销追加反向资金变动恢复资金状态，不删除原结算；之后可用新 settlementId 重新结算。
     */
    @PostMapping("/api/executions/settlements/reversals")
    public ResponseEntity<SettlementReversalResponse> reverseSettlement(
            @Valid @RequestBody ReverseSettlementRequest request) {
        OrderService.ReverseSettlementResult result = orderService.reverseSettlement(
                null, request.reversalId(), request.settlementId(), request.reason());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(SettlementReversalResponse.of(result.reversal(), result.movement()));
    }

    /**
     * 结算撤销（按结算号入口）：POST /api/executions/settlements/{settlementId}/reversals。
     */
    @PostMapping("/api/executions/settlements/{settlementId}/reversals")
    public ResponseEntity<SettlementReversalResponse> reverseSettlementBySettlementId(
            @PathVariable String settlementId,
            @Valid @RequestBody SettlementReversalRequest request) {
        OrderService.ReverseSettlementResult result = orderService.reverseSettlement(
                null, request.reversalId(), settlementId, request.reason());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(SettlementReversalResponse.of(result.reversal(), result.movement()));
    }

    /**
     * 重新结算入口：结算撤销后对同一成交使用新 settlementId 再次调用结算接口
     * （POST /api/executions/{executionId}/settlements）。
     * 链路查询：GET /api/executions/{executionId}/settlement-chain
     */
    @GetMapping("/api/executions/{executionId}/settlement-chain")
    public SettlementChainResponse settlementChain(@PathVariable String executionId) {
        return SettlementChainResponse.from(orderService.getSettlementChain(null, executionId));
    }

    @GetMapping("/api/fund-accounts/{accountId}")
    public FundAccountResponse getFundAccount(@PathVariable String accountId) {
        return FundAccountResponse.from(orderService.getFundAccount(accountId));
    }
}
