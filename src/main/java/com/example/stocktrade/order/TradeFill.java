package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trade_fills")
public class TradeFill {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "execution_id", nullable = false, unique = true, updatable = false, length = 64)
    private String executionId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    protected TradeFill() {
    }

    public TradeFill(String executionId, String orderId, long quantity, BigDecimal price) {
        this.id = UUID.randomUUID().toString();
        this.executionId = executionId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
