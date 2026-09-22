package com.example.stocktrade.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
public class ExecutionRevocationController {

    private final OrderService orderService;

    public ExecutionRevocationController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/revocations")
    public ResponseEntity<ExecutionRevocationResponse> revoke(
            @Valid @RequestBody RevokeExecutionRequest request) {
        OrderService.RevokeExecutionResult result = orderService.revokeExecution(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ExecutionRevocationResponse.of(result.revocation(), result.executedAt()));
    }
}
