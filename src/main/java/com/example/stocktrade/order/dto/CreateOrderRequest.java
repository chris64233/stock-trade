package com.example.stocktrade.order.dto;

import com.example.stocktrade.order.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Locale;

public class CreateOrderRequest {

    @NotBlank(message = "clientOrderId 不能为空")
    @Size(max = 64, message = "clientOrderId 最长 64 个字符")
    private String clientOrderId;

    @NotBlank(message = "accountId 不能为空")
    @Size(max = 64, message = "accountId 最长 64 个字符")
    private String accountId;

    @NotBlank(message = "symbol 不能为空")
    @Size(max = 10, message = "symbol 最长 10 个字符")
    private String symbol;

    @NotNull(message = "side 不能为空，只允许 BUY 或 SELL")
    private OrderSide side;

    @NotNull(message = "quantity 不能为空")
    @Min(value = 1, message = "quantity 必须在 1 到 1000000 之间")
    @Max(value = 1_000_000, message = "quantity 必须在 1 到 1000000 之间")
    private Integer quantity;

    @NotNull(message = "limitPrice 不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "limitPrice 必须大于 0")
    @Digits(integer = 20, fraction = 4, message = "limitPrice 最多保留 4 位小数")
    private BigDecimal limitPrice;

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId == null ? null : clientOrderId.trim();
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId == null ? null : accountId.trim();
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal limitPrice) {
        this.limitPrice = limitPrice;
    }
}
