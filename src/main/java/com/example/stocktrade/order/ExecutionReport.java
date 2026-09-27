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

    @Column(name = "executed_at", columnDefinition = "TIMESTAMP(9)", nullable = false, updatable = false)
    private Instant executedAt;

    @Column(name = "reversed", nullable = false)
    private boolean reversed;

    @Column(name = "reversed_at", columnDefinition = "TIMESTAMP(9)")
    private Instant reversedAt;

    @Column(name = "settled", nullable = false)
    private boolean settled;

    @Column(name = "settled_at", columnDefinition = "TIMESTAMP(9)")
    private Instant settledAt;

    protected ExecutionReport() {
    }

    public ExecutionReport(String executionId, String orderId, long quantity, BigDecimal price) {
        this.id = UUID.randomUUID().toString();
        this.executionId = executionId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = Instant.now();
        this.reversed = false;
        this.settled = false;
    }

    public void markReversed(Instant reversedAt) {
        if (this.reversed) {
            throw new IllegalStateException("成交回报已被撤销");
        }
        if (this.settled) {
            throw new IllegalStateException("成交回报已结算，不能撤销");
        }
        this.reversed = true;
        this.reversedAt = reversedAt;
    }

    public void markSettled(Instant settledAt) {
        if (this.settled) {
            throw new IllegalStateException("成交回报已结算");
        }
        if (this.reversed) {
            throw new IllegalStateException("成交回报已被撤销，不能结算");
        }
        this.settled = true;
        this.settledAt = settledAt;
    }

    /**
     * 结算撤销：只清结算状态（恢复为未结算，允许用新结算号重新结算），
     * 不改变成交撤销标志和委托成交数量。
     */
    public void clearSettlement() {
        if (!this.settled) {
            throw new IllegalStateException("成交回报未结算，不能撤销结算");
        }
        this.settled = false;
        this.settledAt = null;
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

    public boolean isReversed() {
        return reversed;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public boolean isSettled() {
        return settled;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
