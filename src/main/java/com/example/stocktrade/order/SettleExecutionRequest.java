package com.example.stocktrade.order;

import jakarta.validation.constraints.NotNull;

public record SettleExecutionRequest(
        @NotNull(message = "settlementId 不能为空") String settlementId,
        @NotNull(message = "executionId 不能为空") String executionId
) {
}
