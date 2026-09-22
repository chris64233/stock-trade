package com.example.stocktrade.order;

import java.math.BigDecimal;
import java.time.Instant;

public record AmendmentResponse(
        String id,
        String amendmentId,
        String orderId,
        long oldQuantity,
        long newQuantity,
        BigDecimal oldLimitPrice,
        BigDecimal newLimitPrice,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity,
        Instant amendedAt
) {
    public static AmendmentResponse of(OrderAmendment amendment, OrderStatus status) {
        return new AmendmentResponse(
                amendment.getId(),
                amendment.getAmendmentId(),
                amendment.getOrderId(),
                amendment.getOldQuantity(),
                amendment.getNewQuantity(),
                amendment.getOldLimitPrice(),
                amendment.getNewLimitPrice(),
                status,
                amendment.getFilledQuantity(),
                amendment.getNewQuantity() - amendment.getFilledQuantity(),
                amendment.getAmendedAt()
        );
    }
}
