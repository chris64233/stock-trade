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
@Table(name = "execution_settlements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_execution_settlements_settlement_id", columnNames = "settlement_id"),
        @UniqueConstraint(name = "uk_execution_settlements_execution_id", columnNames = "execution_id")
})
public class ExecutionSettlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "settlement_id", nullable = false, updatable = false, length = 64)
    private String settlementId;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 64)
    private String executionId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status_at_settlement", nullable = false, updatable = false, length = 16)
    private OrderStatus orderStatus;

    @Column(name = "filled_quantity_at_settlement", nullable = false, updatable = false)
    private long filledQuantity;

    @Column(name = "remaining_quantity_at_settlement", nullable = false, updatable = false)
    private long remainingQuantity;

    protected ExecutionSettlement() {
    }

    public ExecutionSettlement(String settlementId, ExecutionReport report, OrderStatus orderStatus,
                               long filledQuantity, long remainingQuantity) {
        this.id = UUID.randomUUID().toString();
        this.settlementId = settlementId;
        this.executionId = report.getExecutionId();
        this.orderId = report.getOrderId();
        this.quantity = report.getQuantity();
        this.price = report.getPrice();
        this.settledAt = Instant.now();
        this.orderStatus = orderStatus;
        this.filledQuantity = filledQuantity;
        this.remainingQuantity = remainingQuantity;
    }

    public String getId() {
        return id;
    }

    public String getSettlementId() {
        return settlementId;
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

    public Instant getSettledAt() {
        return settledAt;
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
