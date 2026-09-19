package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_orders")
public class StockOrder {

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(nullable = false, unique = true, updatable = false, length = 64)
    private String clientOrderId;

    @Column(nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private OrderSide side;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false, precision = 24, scale = 4)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant cancelledAt;

    protected StockOrder() {
    }

    public StockOrder(String clientOrderId, String accountId, String symbol, OrderSide side,
                      int quantity, BigDecimal limitPrice) {
        this.id = UUID.randomUUID().toString();
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.status = OrderStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public void cancel() {
        if (this.status == OrderStatus.OPEN) {
            this.status = OrderStatus.CANCELLED;
            this.cancelledAt = Instant.now();
        }
    }

    public boolean matches(String accountId, String symbol, OrderSide side, int quantity, BigDecimal limitPrice) {
        return this.accountId.equals(accountId)
                && this.symbol.equals(symbol)
                && this.side == side
                && this.quantity == quantity
                && this.limitPrice.compareTo(limitPrice) == 0;
    }

    public String getId() {
        return id;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
