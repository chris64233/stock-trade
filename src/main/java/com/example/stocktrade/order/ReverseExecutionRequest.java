package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record ReverseExecutionRequest(
        @NotNull(message = "reversalId 不能为空") String reversalId,
        @NotNull(message = "executionId 不能为空") String executionId
) {
}
