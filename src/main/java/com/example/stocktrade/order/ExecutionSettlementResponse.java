package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionSettlementResponse(
        String id,
        String settlementId,
        String executionId,
        String orderId,
        String accountId,
        long quantity,
        BigDecimal price,
        BigDecimal settlementAmount,
        Instant settledAt,
        boolean settled,
        boolean reversed,
        Instant reversedAt,
        String reversalId,
        String replacesSettlementId,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity,
        BigDecimal signedDelta,
        BigDecimal balanceAfter
) {
    public static ExecutionSettlementResponse of(ExecutionSettlement settlement,
                                                 SettlementFundMovement movement) {
        BigDecimal amount = settlement.getPrice().multiply(BigDecimal.valueOf(settlement.getQuantity()));
        return new ExecutionSettlementResponse(
                settlement.getId(),
                settlement.getSettlementId(),
                settlement.getExecutionId(),
                settlement.getOrderId(),
                settlement.getAccountId(),
                settlement.getQuantity(),
                settlement.getPrice(),
                amount,
                settlement.getSettledAt(),
                !settlement.isReversed(),
                settlement.isReversed(),
                settlement.getReversedAt(),
                settlement.getReversalId(),
                settlement.getReplacesSettlementId(),
                settlement.getOrderStatusAfter(),
                settlement.getFilledQuantityAfter(),
                settlement.getRemainingQuantityAfter(),
                movement == null ? null : movement.getSignedDelta(),
                movement == null ? null : movement.getBalanceAfter()
        );
    }

    public static ExecutionSettlementResponse from(ExecutionSettlement settlement) {
        return of(settlement, null);
    }
}
