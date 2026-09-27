package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record ReverseSettlementRequest(
        @NotNull(message = "reversalId 不能为空") String reversalId,
        @NotNull(message = "settlementId 不能为空") String settlementId,
        @NotNull(message = "reason 不能为空") String reason
) {
}
