package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_revocations")
public class ExecutionRevocation {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "revoke_id", nullable = false, unique = true, updatable = false, length = 64)
    private String revokeId;

    @Column(name = "execution_id", nullable = false, unique = true, updatable = false, length = 64)
    private String executionId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private Instant revokedAt;

    @Column(name = "order_status", nullable = false, updatable = false, length = 16)
    private OrderStatus orderStatus;

    @Column(name = "filled_quantity", nullable = false, updatable = false)
    private long filledQuantity;

    @Column(name = "remaining_quantity", nullable = false, updatable = false)
    private long remainingQuantity;

    protected ExecutionRevocation() {
    }

    public ExecutionRevocation(String revokeId, String executionId, String orderId, long quantity,
                               BigDecimal price, OrderStatus orderStatus, long filledQuantity,
                               long remainingQuantity) {
        this.id = UUID.randomUUID().toString();
        this.revokeId = revokeId;
        this.executionId = executionId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.price = price;
        this.revokedAt = Instant.now();
        this.orderStatus = orderStatus;
        this.filledQuantity = filledQuantity;
        this.remainingQuantity = remainingQuantity;
    }

    public String getId() {
        return id;
    }

    public String getRevokeId() {
        return revokeId;
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

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }
}
