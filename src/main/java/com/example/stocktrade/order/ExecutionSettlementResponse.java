package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionSettlementResponse(
        String id,
        String settlementId,
        String executionId,
        String orderId,
        long quantity,
        BigDecimal price,
        Instant settledAt,
        boolean settled,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity
) {
    public static ExecutionSettlementResponse from(ExecutionSettlement settlement) {
        return new ExecutionSettlementResponse(
                settlement.getId(),
                settlement.getSettlementId(),
                settlement.getExecutionId(),
                settlement.getOrderId(),
                settlement.getQuantity(),
                settlement.getPrice(),
                settlement.getSettledAt(),
                true,
                settlement.getOrderStatusAfter(),
                settlement.getFilledQuantityAfter(),
                settlement.getRemainingQuantityAfter()
        );
    }
}
