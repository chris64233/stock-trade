package com.example.stocktrade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 资金账户状态。结算/结算撤销在同一事务内追加资金变动并同步更新余额，
 * 反向撤销使余额恢复到结算前状态。
 */
@Entity
@Table(name = "fund_accounts")
public class FundAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64, unique = true)
    private String accountId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FundAccount() {
    }

    public FundAccount(String accountId) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.balance = BigDecimal.ZERO;
    }

    public void apply(BigDecimal signedDelta) {
        this.balance = this.balance.add(signedDelta);
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
