package com.example.stocktrade.order;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExecutionPageResponse(
        String orderId,
        OrderStatus orderStatus,
        long filledQuantity,
        long remainingQuantity,
        List<ExecutionItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ExecutionPageResponse from(StockOrder order, Page<ExecutionReport> executions,
                                             Map<String, ExecutionSettlement> settlements) {
        return new ExecutionPageResponse(
                order.getId(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getRemainingQuantity(),
                executions.getContent().stream()
                        .map(report -> ExecutionItem.from(report, settlements.get(report.getExecutionId())))
                        .toList(),
                executions.getNumber(),
                executions.getSize(),
                executions.getTotalElements(),
                executions.getTotalPages()
        );
    }

    public record ExecutionItem(
            String id,
            String executionId,
            String orderId,
            long quantity,
            BigDecimal price,
            Instant executedAt,
            boolean reversed,
            Instant reversedAt,
            boolean settled,
            Instant settledAt,
            String settlementId
    ) {
        public static ExecutionItem from(ExecutionReport report, ExecutionSettlement settlement) {
            return new ExecutionItem(
                    report.getId(),
                    report.getExecutionId(),
                    report.getOrderId(),
                    report.getQuantity(),
                    report.getPrice(),
                    report.getExecutedAt(),
                    report.isReversed(),
                    report.getReversedAt(),
                    report.isSettled(),
                    report.getSettledAt(),
                    settlement == null ? null : settlement.getSettlementId()
            );
        }
    }
}
