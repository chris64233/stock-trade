package com.example.stocktrade.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExecutionReversalController {

    private final OrderService orderService;

    public ExecutionReversalController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/executions/reversals")
    public ResponseEntity<ExecutionReversalResponse> reverse(
            @Valid @RequestBody ReverseExecutionRequest request) {
        OrderService.ReverseExecutionResult result =
                orderService.reverseExecution(null, request.reversalId(), request.executionId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionReversalResponse.from(result.reversal()));
    }

    @PostMapping("/api/executions/{executionId}/reversals")
    public ResponseEntity<ExecutionReversalResponse> reverseByExecutionId(
            @PathVariable String executionId,
            @Valid @RequestBody ExecutionReversalRequest request) {
        OrderService.ReverseExecutionResult result =
                orderService.reverseExecution(null, request.reversalId(), executionId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionReversalResponse.from(result.reversal()));
    }
}
