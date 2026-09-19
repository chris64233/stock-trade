package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionResponse(
        String id,
        String executionId,
        String orderId,
        long quantity,
        BigDecimal price,
        Instant executedAt,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity
) {
    public static ExecutionResponse of(ExecutionReport report, StockOrder order) {
        return new ExecutionResponse(
                report.getId(),
                report.getExecutionId(),
                report.getOrderId(),
                report.getQuantity(),
                report.getPrice(),
                report.getExecutedAt(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getRemainingQuantity()
        );
    }
}
