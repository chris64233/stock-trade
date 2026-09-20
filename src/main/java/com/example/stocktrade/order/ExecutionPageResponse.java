package com.example.stocktrade.order;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ExecutionPageResponse(
        String orderId,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity,
        List<Item> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public record Item(
            String id,
            String executionId,
            String orderId,
            long quantity,
            BigDecimal price,
            Instant executedAt
    ) {
        static Item from(ExecutionReport report) {
            return new Item(
                    report.getId(),
                    report.getExecutionId(),
                    report.getOrderId(),
                    report.getQuantity(),
                    report.getPrice(),
                    report.getExecutedAt()
            );
        }
    }

    public static ExecutionPageResponse of(StockOrder order, Page<ExecutionReport> page) {
        return new ExecutionPageResponse(
                order.getId(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getRemainingQuantity(),
                page.getContent().stream().map(Item::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
