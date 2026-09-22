package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_reports")
public class ExecutionReport {

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

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "revoke_id", length = 64)
    private String revokeId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ExecutionReport() {
    }

    public ExecutionReport(String executionId, String orderId, long quantity, BigDecimal price) {
        this.id = UUID.randomUUID().toString();
        this.executionId = executionId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = Instant.now();
    }

    public boolean matches(String orderId, long quantity, BigDecimal price) {
        return this.orderId.equals(orderId)
                && this.quantity == quantity
                && this.price.compareTo(price) == 0;
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

    public boolean isRevoked() {
        return revoked;
    }

    public String getRevokeId() {
        return revokeId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void markRevoked(String revokeId) {
        if (this.revoked) {
            throw new IllegalStateException("成交回报已撤销");
        }
        this.revoked = true;
        this.revokeId = revokeId;
        this.revokedAt = Instant.now();
    }
}
