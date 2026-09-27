package com.example.stocktrade.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateOrderResult result = orderService.create(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable String id) {
        return OrderResponse.from(orderService.getById(id));
    }

    @GetMapping("/{id}/execution-summary")
    public ExecutionSummaryResponse getExecutionSummary(@PathVariable String id) {
        return orderService.getExecutionSummary(id);
    }

    @GetMapping
    public OrderPageResponse search(@RequestParam(required = false) String accountId,
                                    @RequestParam(required = false) String symbol,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String side,
                                    @RequestParam(defaultValue = "0") String page,
                                    @RequestParam(defaultValue = "20") String size) {
        return OrderPageResponse.from(orderService.search(accountId, symbol, status, side, page, size));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable String id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    @PostMapping("/{id}/amendments")
    public ResponseEntity<AmendOrderResponse> amend(@PathVariable String id,
                                                    @Valid @RequestBody AmendOrderRequest request) {
        OrderService.AmendOrderResult result = orderService.amend(id, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(AmendOrderResponse.from(result.amendment()));
    }

    @PostMapping("/{id}/executions")
    public ResponseEntity<ExecutionResponse> registerExecution(@PathVariable String id,
                                                               @Valid @RequestBody RegisterExecutionRequest request) {
        OrderService.RegisterExecutionResult result = orderService.registerExecution(id, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionResponse.of(result.report(), result.order()));
    }

    @PostMapping("/{id}/executions/reversals")
    public ResponseEntity<ExecutionReversalResponse> reverseExecution(@PathVariable String id,
                                                                      @Valid @RequestBody ReverseExecutionRequest request) {
        OrderService.ReverseExecutionResult result =
                orderService.reverseExecution(id, request.reversalId(), request.executionId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionReversalResponse.from(result.reversal()));
    }

    @PostMapping("/{id}/executions/settlements")
    public ResponseEntity<ExecutionSettlementResponse> settleExecution(@PathVariable String id,
                                                                       @Valid @RequestBody SettleExecutionRequest request) {
        OrderService.SettleExecutionResult result =
                orderService.settleExecution(id, request.settlementId(), request.executionId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ExecutionSettlementResponse.of(result.settlement(), result.movement()));
    }

    /**
     * 结算撤销（按委托入口）：请求体含 reversalId、settlementId、reason。
     */
    @PostMapping("/{id}/executions/settlements/reversals")
    public ResponseEntity<SettlementReversalResponse> reverseSettlement(@PathVariable String id,
                                                                        @Valid @RequestBody ReverseSettlementRequest request) {
        OrderService.ReverseSettlementResult result = orderService.reverseSettlement(
                id, request.reversalId(), request.settlementId(), request.reason());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(SettlementReversalResponse.of(result.reversal(), result.movement()));
    }

    /**
     * 结算撤销（按成交入口）：POST /api/orders/{id}/executions/{executionId}/settlement-reversal
     * 撤销的是该成交当前生效的结算；撤销后可用新结算号走结算接口重新结算。
     */
    @PostMapping("/{id}/executions/{executionId}/settlement-reversal")
    public ResponseEntity<SettlementReversalResponse> reverseActiveSettlement(@PathVariable String id,
                                                                              @PathVariable String executionId,
                                                                              @Valid @RequestBody SettlementReversalRequest request) {
        String settlementId = orderService.getSettlementChain(id, executionId).currentSettlementId();
        if (settlementId == null) {
            throw new com.example.stocktrade.error.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT, "SETTLEMENT_NOT_FOUND",
                    "成交当前没有生效的结算，不能撤销");
        }
        OrderService.ReverseSettlementResult result = orderService.reverseSettlement(
                id, request.reversalId(), settlementId, request.reason());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(SettlementReversalResponse.of(result.reversal(), result.movement()));
    }

    @GetMapping("/{id}/executions/{executionId}/settlement-chain")
    public SettlementChainResponse settlementChain(@PathVariable String id,
                                                   @PathVariable String executionId) {
        return SettlementChainResponse.from(orderService.getSettlementChain(id, executionId));
    }

    @GetMapping("/{id}/executions")
    public ExecutionPageResponse listExecutions(@PathVariable String id,
                                                @RequestParam(required = false) String page,
                                                @RequestParam(required = false) String size) {
        OrderService.OrderExecutionsResult result = orderService.listExecutions(id, page, size);
        return ExecutionPageResponse.from(result.order(), result.executions(),
                result.settlementsByExecutionId());
    }
}
