package com.example.stocktrade.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        return ResponseEntity.status(status).body(ExecutionSettlementResponse.from(result.settlement()));
    }

    @PostMapping("/api/executions/{executionId}/settlements")
    public ResponseEntity<ExecutionSettlementResponse> settleByExecutionId(
            @PathVariable String executionId,
            @Valid @RequestBody ExecutionSettlementRequest request) {
        OrderService.SettleExecutionResult result =
                orderService.settleExecution(null, request.settlementId(), executionId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionSettlementResponse.from(result.settlement()));
    }
}
