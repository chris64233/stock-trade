package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionRevocationResponse(
        String revocationId,
        String revokeId,
        String executionId,
        String orderId,
        long quantity,
        BigDecimal price,
        Instant executedAt,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity,
        Instant revokedAt
) {
    public static ExecutionRevocationResponse of(ExecutionRevocation revocation, Instant executedAt) {
        return new ExecutionRevocationResponse(
                revocation.getId(),
                revocation.getRevokeId(),
                revocation.getExecutionId(),
                revocation.getOrderId(),
                revocation.getQuantity(),
                revocation.getPrice(),
                executedAt,
                revocation.getOrderStatus(),
                revocation.getFilledQuantity(),
                revocation.getRemainingQuantity(),
                revocation.getRevokedAt()
        );
    }
}
