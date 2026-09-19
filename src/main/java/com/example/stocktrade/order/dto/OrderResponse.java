package com.example.stocktrade.order.dto;

import com.example.stocktrade.order.OrderSide;
import com.example.stocktrade.order.OrderStatus;
import com.example.stocktrade.order.StockOrder;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String id,
        String clientOrderId,
        String accountId,
        String symbol,
        OrderSide side,
        int quantity,
        BigDecimal limitPrice,
        OrderStatus status,
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
                order.getCreatedAt(),
                order.getCancelledAt()
        );
    }
}
