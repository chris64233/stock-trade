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

    @PostMapping("/{id}/executions")
    public ResponseEntity<ExecutionResponse> registerExecution(@PathVariable String id,
                                                               @Valid @RequestBody RegisterExecutionRequest request) {
        OrderService.RegisterExecutionResult result = orderService.registerExecution(id, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ExecutionResponse.of(result.report(), result.order()));
    }

    @GetMapping("/{id}/executions")
    public ExecutionPageResponse listExecutions(@PathVariable String id,
                                                @RequestParam(required = false) String page,
                                                @RequestParam(required = false) String size) {
        OrderService.OrderExecutionsResult result = orderService.listExecutions(id, page, size);
        return ExecutionPageResponse.from(result.order(), result.executions());
    }
}
