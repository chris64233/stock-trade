package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record ExecutionSettlementRequest(
        @NotNull(message = "settlementId 不能为空") String settlementId
) {
}
