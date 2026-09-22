package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record RevokeExecutionRequest(
        @NotNull(message = "revokeId 不能为空") String revokeId,
        @NotNull(message = "executionId 不能为空") String executionId
) {
}
