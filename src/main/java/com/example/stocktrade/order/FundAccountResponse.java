package com.example.stocktrade.order;

import java.math.BigDecimal;

public record FundAccountResponse(
        String accountId,
        BigDecimal balance
) {
    public static FundAccountResponse from(FundAccount account) {
        return new FundAccountResponse(account.getAccountId(), account.getBalance());
    }
}
