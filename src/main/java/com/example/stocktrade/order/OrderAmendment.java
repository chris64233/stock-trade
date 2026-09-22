package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_amendments")
public class OrderAmendment {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "amendment_id", nullable = false, unique = true, updatable = false, length = 64)
    private String amendmentId;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "old_quantity", nullable = false, updatable = false)
    private long oldQuantity;

    @Column(name = "new_quantity", nullable = false, updatable = false)
    private long newQuantity;

    @Column(name = "old_limit_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal oldLimitPrice;

    @Column(name = "new_limit_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal newLimitPrice;

    @Column(name = "filled_quantity", nullable = false, updatable = false)
    private long filledQuantity;

    @Column(name = "amended_at", nullable = false, updatable = false)
    private Instant amendedAt;

    protected OrderAmendment() {
    }

    public OrderAmendment(String amendmentId, String orderId, long oldQuantity, long newQuantity,
                          BigDecimal oldLimitPrice, BigDecimal newLimitPrice, long filledQuantity) {
        this.id = UUID.randomUUID().toString();
        this.amendmentId = amendmentId;
        this.orderId = orderId;
        this.oldQuantity = oldQuantity;
        this.newQuantity = newQuantity;
        this.oldLimitPrice = oldLimitPrice;
        this.newLimitPrice = newLimitPrice;
        this.filledQuantity = filledQuantity;
        this.amendedAt = Instant.now();
    }

    public boolean matches(String orderId, long quantity, BigDecimal limitPrice) {
        return this.orderId.equals(orderId)
                && this.newQuantity == quantity
                && this.newLimitPrice.compareTo(limitPrice) == 0;
    }

    public String getId() {
        return id;
    }

    public String getAmendmentId() {
        return amendmentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getOldQuantity() {
        return oldQuantity;
    }

    public long getNewQuantity() {
        return newQuantity;
    }

    public BigDecimal getOldLimitPrice() {
        return oldLimitPrice;
    }

    public BigDecimal getNewLimitPrice() {
        return newLimitPrice;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public Instant getAmendedAt() {
        return amendedAt;
    }
}
