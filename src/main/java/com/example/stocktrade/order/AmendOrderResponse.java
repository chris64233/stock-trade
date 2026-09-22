package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record AmendOrderResponse(
        String amendmentId,
        String amendId,
        String orderId,
        long oldQuantity,
        long newQuantity,
        BigDecimal oldLimitPrice,
        BigDecimal newLimitPrice,
        long filledQuantity,
        long remainingQuantity,
        OrderStatus status,
        Instant amendedAt
) {
    public static AmendOrderResponse from(OrderAmendment amendment) {
        return new AmendOrderResponse(
                amendment.getId(),
                amendment.getAmendId(),
                amendment.getOrderId(),
                amendment.getOldQuantity(),
                amendment.getNewQuantity(),
                amendment.getOldLimitPrice(),
                amendment.getNewLimitPrice(),
                amendment.getFilledQuantity(),
                amendment.getNewQuantity() - amendment.getFilledQuantity(),
                amendment.getFilledQuantity() == 0 ? OrderStatus.OPEN : OrderStatus.PARTIALLY_FILLED,
                amendment.getAmendedAt()
        );
    }
}
