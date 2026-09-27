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
 * 结算资金变动记录（追加式，不可修改）。
 * 结算写入一笔 SETTLEMENT 变动；结算撤销追加一笔 REVERSAL 反向变动把资金恢复原状，
 * 不删除原记录。重新结算再追加新的 SETTLEMENT 变动，三者共同还原整条资金变化链。
 */
@Entity
@Table(name = "settlement_fund_movements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fund_movements_settlement_id_type",
                columnNames = {"settlement_id", "type"}),
        @UniqueConstraint(name = "uk_fund_movements_reversal_id", columnNames = "reversal_id")
})
public class SettlementFundMovement {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 64)
    private String executionId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "settlement_id", nullable = false, updatable = false, length = 64)
    private String settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private FundMovementType type;

    @Column(name = "reversal_id", updatable = false, length = 64)
    private String reversalId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "signed_delta", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal signedDelta;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP(9)", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementFundMovement() {
    }

    private SettlementFundMovement(String accountId, ExecutionReport report, String settlementId,
                                   FundMovementType type, String reversalId, BigDecimal signedDelta,
                                   BigDecimal balanceAfter) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.executionId = report.getExecutionId();
        this.orderId = report.getOrderId();
        this.settlementId = settlementId;
        this.type = type;
        this.reversalId = reversalId;
        this.quantity = report.getQuantity();
        this.price = report.getPrice();
        this.amount = report.getPrice().multiply(BigDecimal.valueOf(report.getQuantity()));
        this.signedDelta = signedDelta;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    public static SettlementFundMovement settlement(String accountId, ExecutionReport report,
                                                    String settlementId, BigDecimal signedDelta,
                                                    BigDecimal balanceAfter) {
        return new SettlementFundMovement(accountId, report, settlementId,
                FundMovementType.SETTLEMENT, null, signedDelta, balanceAfter);
    }

    public static SettlementFundMovement reversal(String accountId, ExecutionReport report,
                                                  String settlementId, String reversalId,
                                                  BigDecimal signedDelta, BigDecimal balanceAfter) {
        return new SettlementFundMovement(accountId, report, settlementId,
                FundMovementType.REVERSAL, reversalId, signedDelta, balanceAfter);
    }

    public static BigDecimal signedDelta(ExecutionReport report, OrderSide side, boolean reverse) {
        BigDecimal amount = report.getPrice().multiply(BigDecimal.valueOf(report.getQuantity()));
        // 卖出结算收回现金（正向），买入结算支付现金（反向）；撤销结算取相反方向
        boolean positive = (side == OrderSide.SELL) != reverse;
        return positive ? amount : amount.negate();
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public FundMovementType getType() {
        return type;
    }

    public String getReversalId() {
        return reversalId;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getSignedDelta() {
        return signedDelta;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
