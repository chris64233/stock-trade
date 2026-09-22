package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionReversalResponse(
        String id,
        String reversalId,
        String executionId,
        String orderId,
        long quantity,
        BigDecimal price,
        Instant reversedAt,
        boolean reversed,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity
) {
    public static ExecutionReversalResponse from(ExecutionReversal reversal) {
        return new ExecutionReversalResponse(
                reversal.getId(),
                reversal.getReversalId(),
                reversal.getExecutionId(),
                reversal.getOrderId(),
                reversal.getQuantity(),
                reversal.getPrice(),
                reversal.getReversedAt(),
                true,
                reversal.getOrderStatusAfter(),
                reversal.getFilledQuantityAfter(),
                reversal.getRemainingQuantityAfter()
        );
    }
}
