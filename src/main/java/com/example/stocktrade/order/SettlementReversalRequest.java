package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record SettlementReversalRequest(
        @NotNull(message = "reversalId 不能为空") String reversalId,
        @NotNull(message = "reason 不能为空") String reason
) {
}
