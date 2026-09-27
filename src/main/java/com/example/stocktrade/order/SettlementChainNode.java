package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算变化链上的一个节点：原结算、结算撤销或重新结算，按发生时间排列即可还原整条变化链。
 */
public record SettlementChainNode(
        String type,
        String settlementId,
        String reversalId,
        String reason,
        String replacesSettlementId,
        boolean reversed,
        long quantity,
        BigDecimal price,
        BigDecimal signedDelta,
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public static SettlementChainNode settlement(ExecutionSettlement settlement,
                                                 SettlementFundMovement movement) {
        return new SettlementChainNode(
                "SETTLEMENT",
                settlement.getSettlementId(),
                settlement.getReversalId(),
                null,
                settlement.getReplacesSettlementId(),
                settlement.isReversed(),
                settlement.getQuantity(),
                settlement.getPrice(),
                movement == null ? null : movement.getSignedDelta(),
                movement == null ? null : movement.getBalanceAfter(),
                settlement.getSettledAt()
        );
    }

    public static SettlementChainNode reversal(SettlementReversal reversal,
                                               SettlementFundMovement movement) {
        return new SettlementChainNode(
                "REVERSAL",
                reversal.getSettlementId(),
                reversal.getReversalId(),
                reversal.getReason(),
                null,
                true,
                reversal.getQuantity(),
                reversal.getPrice(),
                movement == null ? null : movement.getSignedDelta(),
                movement == null ? null : movement.getBalanceAfter(),
                reversal.getReversedAt()
        );
    }
}
