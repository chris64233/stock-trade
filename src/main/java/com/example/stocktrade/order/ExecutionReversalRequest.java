package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record ExecutionReversalRequest(
        @NotNull(message = "reversalId 不能为空") String reversalId
) {
}
