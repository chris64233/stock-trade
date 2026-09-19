package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record FillResponse(
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
    public static FillResponse from(TradeFill fill, StockOrder order) {
        return new FillResponse(
                fill.getId(),
                fill.getExecutionId(),
                fill.getOrderId(),
                fill.getQuantity(),
                fill.getPrice(),
                fill.getExecutedAt(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getRemainingQuantity()
        );
    }
}
