package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record SettlementReversalResponse(
        String id,
        String reversalId,
        String settlementId,
        String executionId,
        String orderId,
        String accountId,
        long quantity,
        BigDecimal price,
        String reason,
        Instant reversedAt,
        BigDecimal signedDelta,
        BigDecimal balanceAfter
) {
    public static SettlementReversalResponse of(SettlementReversal reversal,
                                                SettlementFundMovement movement) {
        return new SettlementReversalResponse(
                reversal.getId(),
                reversal.getReversalId(),
                reversal.getSettlementId(),
                reversal.getExecutionId(),
                reversal.getOrderId(),
                reversal.getAccountId(),
                reversal.getQuantity(),
                reversal.getPrice(),
                reversal.getReason(),
                reversal.getReversedAt(),
                movement.getSignedDelta(),
                movement.getBalanceAfter()
        );
    }
}
