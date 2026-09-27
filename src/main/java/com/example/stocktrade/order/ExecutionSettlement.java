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

/**
 * 成交结算审计记录（追加式，不可删除）。
 * 同一成交可以沿「原结算 -> 结算撤销 -> 新结算」形成链路：
 * 被撤销的结算保留并置 {@code reversed=true}，重新结算写入新记录并通过
 * {@code replacesSettlementId} 回指被替换的结算；{@code settlementId} 全局唯一，
 * 不再对 {@code executionId} 加唯一约束。
 */
@Entity
@Table(name = "execution_settlements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_execution_settlements_settlement_id", columnNames = "settlement_id")
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

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "settled_at", columnDefinition = "TIMESTAMP(9)", nullable = false, updatable = false)
    private Instant settledAt;

    @Column(name = "replaces_settlement_id", updatable = false, length = 64)
    private String replacesSettlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status_after", nullable = false, updatable = false, length = 16)
    private OrderStatus orderStatusAfter;

    @Column(name = "filled_quantity_after", nullable = false, updatable = false)
    private long filledQuantityAfter;

    @Column(name = "remaining_quantity_after", nullable = false, updatable = false)
    private long remainingQuantityAfter;

    @Column(name = "reversed", nullable = false)
    private boolean reversed;

    @Column(name = "reversed_at", columnDefinition = "TIMESTAMP(9)")
    private Instant reversedAt;

    @Column(name = "reversal_id", updatable = false, length = 64)
    private String reversalId;

    protected ExecutionSettlement() {
    }

    public ExecutionSettlement(String settlementId, ExecutionReport report, String accountId,
                               String replacesSettlementId, OrderStatus orderStatusAfter,
                               long filledQuantityAfter, long remainingQuantityAfter) {
        this.id = UUID.randomUUID().toString();
        this.settlementId = settlementId;
        this.executionId = report.getExecutionId();
        this.orderId = report.getOrderId();
        this.accountId = accountId;
        this.quantity = report.getQuantity();
        this.price = report.getPrice();
        this.settledAt = Instant.now();
        this.replacesSettlementId = replacesSettlementId;
        this.orderStatusAfter = orderStatusAfter;
        this.filledQuantityAfter = filledQuantityAfter;
        this.remainingQuantityAfter = remainingQuantityAfter;
        this.reversed = false;
    }

    public void markReversed(String reversalId, Instant reversedAt) {
        if (this.reversed) {
            throw new IllegalStateException("结算已被撤销");
        }
        this.reversed = true;
        this.reversedAt = reversedAt;
        this.reversalId = reversalId;
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

    public String getAccountId() {
        return accountId;
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

    public String getReplacesSettlementId() {
        return replacesSettlementId;
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

    public boolean isReversed() {
        return reversed;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public String getReversalId() {
        return reversalId;
    }
}
