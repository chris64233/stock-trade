package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionSummaryResponse(
        String orderId,
        OrderStatus status,
        long orderQuantity,
        long filledQuantity,
        long remainingQuantity,
        long executionCount,
        BigDecimal totalExecutedAmount,
        BigDecimal averageExecutionPrice,
        Instant lastExecutedAt
) {
}
