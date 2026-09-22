package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_reversals", uniqueConstraints = {
        @UniqueConstraint(name = "uk_execution_reversals_reversal_id", columnNames = "reversal_id"),
        @UniqueConstraint(name = "uk_execution_reversals_execution_id", columnNames = "execution_id")
})
public class ExecutionReversal {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "reversal_id", nullable = false, updatable = false, length = 64)
    private String reversalId;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 64)
    private String executionId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status_after", nullable = false, updatable = false, length = 16)
    private OrderStatus orderStatusAfter;

    @Column(name = "filled_quantity_after", nullable = false, updatable = false)
    private long filledQuantityAfter;

    @Column(name = "remaining_quantity_after", nullable = false, updatable = false)
    private long remainingQuantityAfter;

    protected ExecutionReversal() {
    }

    public ExecutionReversal(String reversalId, ExecutionReport report, OrderStatus orderStatusAfter,
                             long filledQuantityAfter, long remainingQuantityAfter) {
        this.id = UUID.randomUUID().toString();
        this.reversalId = reversalId;
        this.executionId = report.getExecutionId();
        this.orderId = report.getOrderId();
        this.quantity = report.getQuantity();
        this.price = report.getPrice();
        this.reversedAt = Instant.now();
        this.orderStatusAfter = orderStatusAfter;
        this.filledQuantityAfter = filledQuantityAfter;
        this.remainingQuantityAfter = remainingQuantityAfter;
    }

    public String getId() {
        return id;
    }

    public String getReversalId() {
        return reversalId;
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

    public Instant getReversedAt() {
        return reversedAt;
    }

    public OrderStatus getOrderStatusAfter() {
        return orderStatusAfter;
    }

    public long getFilledQuantityAfter() {
        return filledQuantityAfter;
    }

    public long getRemainingQuantityAfter() {
        return remainingQuantityAfter;
    }
}
