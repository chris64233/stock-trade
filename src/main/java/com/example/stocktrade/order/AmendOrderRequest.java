package com.example.stocktrade.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmendOrderRequest(
        @NotNull(message = "amendmentId 不能为空") String amendmentId,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "quantity 必须在 1 到 1000000 之间")
        @Max(value = 1_000_000, message = "quantity 必须在 1 到 1000000 之间")
        Integer quantity,
        @NotNull(message = "limitPrice 不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "limitPrice 必须大于 0")
        @Digits(integer = 14, fraction = 4, message = "limitPrice 最多保留 4 位小数")
        BigDecimal limitPrice
) {
}
