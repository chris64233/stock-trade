package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 结算撤销审计记录（追加式，不可修改）。
 * 不删除原结算，而是通过 {@code reversedSettlementId} 指向被撤销的原结算，
 * 并由重新结算记录通过其 {@code replacesSettlementId} 回指，组成不可变关系链。
 * reversalId 全局唯一保证幂等；每笔结算至多一条撤销（settlement_id 唯一）。
 */
@Entity
@Table(name = "settlement_reversals", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_reversals_reversal_id", columnNames = "reversal_id"),
        @UniqueConstraint(name = "uk_settlement_reversals_settlement_id", columnNames = "settlement_id")
})
public class SettlementReversal {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "reversal_id", nullable = false, updatable = false, length = 64)
    private String reversalId;

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

    @Column(name = "reason", nullable = false, updatable = false, length = 256)
    private String reason;

    @Column(name = "reversed_at", columnDefinition = "TIMESTAMP(9)", nullable = false, updatable = false)
    private Instant reversedAt;

    protected SettlementReversal() {
    }

    public SettlementReversal(String reversalId, ExecutionSettlement settlement,
                              ExecutionReport report, String accountId, String reason) {
        this.id = UUID.randomUUID().toString();
        this.reversalId = reversalId;
        this.settlementId = settlement.getSettlementId();
        this.executionId = report.getExecutionId();
        this.orderId = report.getOrderId();
        this.accountId = accountId;
        this.quantity = report.getQuantity();
        this.price = report.getPrice();
        this.reason = reason;
        this.reversedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getReversalId() {
        return reversalId;
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

    public String getReason() {
        return reason;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
