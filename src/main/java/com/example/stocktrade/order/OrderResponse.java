package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String id,
        String clientOrderId,
        String accountId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal limitPrice,
        OrderStatus status,
        long filledQuantity,
        long remainingQuantity,
        Instant createdAt,
        Instant cancelledAt
) {
    public static OrderResponse from(StockOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getLimitPrice(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getRemainingQuantity(),
                order.getCreatedAt(),
                order.getCancelledAt()
        );
    }
}
