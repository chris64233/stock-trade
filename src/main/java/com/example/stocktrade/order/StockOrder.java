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
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "client_order_id", nullable = false, unique = true, updatable = false, length = 64)
    private String clientOrderId;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(name = "symbol", nullable = false, updatable = false, length = 10)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, updatable = false, length = 4)
    private OrderSide side;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "limit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected StockOrder() {
    }

    public StockOrder(String clientOrderId, String accountId, String symbol, OrderSide side,
                      long quantity, BigDecimal limitPrice) {
        this.id = UUID.randomUUID().toString();
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.status = OrderStatus.OPEN;
        this.filledQuantity = 0;
        this.createdAt = Instant.now();
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

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void applyFill(long fillQuantity) {
        this.filledQuantity += fillQuantity;
        this.status = this.filledQuantity >= this.quantity
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;
    }

    public void amend(long newQuantity, BigDecimal newLimitPrice) {
        if (this.status != OrderStatus.OPEN && this.status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("只有 OPEN 或 PARTIALLY_FILLED 状态的委托可以改单");
        }
        if (newQuantity <= this.filledQuantity) {
            throw new IllegalStateException("改单后的总数量必须严格大于已成交数量");
        }
        this.quantity = newQuantity;
        this.limitPrice = newLimitPrice;
    }

    public void cancel() {
        if (this.status == OrderStatus.OPEN || this.status == OrderStatus.PARTIALLY_FILLED) {
            this.status = OrderStatus.CANCELLED;
            this.cancelledAt = Instant.now();
        }
    }

    public void reverseFill(long reversedQuantity) {
        if (reversedQuantity <= 0 || reversedQuantity > this.filledQuantity) {
            throw new IllegalStateException("撤销数量不能超过已成交数量");
        }
        this.filledQuantity -= reversedQuantity;
        if (this.status != OrderStatus.CANCELLED) {
            this.status = this.filledQuantity == 0
                    ? OrderStatus.OPEN
                    : OrderStatus.PARTIALLY_FILLED;
        }
    }
}
