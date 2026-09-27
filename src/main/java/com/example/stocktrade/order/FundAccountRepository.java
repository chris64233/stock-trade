package com.example.stocktrade.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FundAccountRepository extends JpaRepository<FundAccount, String> {

    Optional<FundAccount> findByAccountId(String accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FundAccount f where f.accountId = :accountId")
    Optional<FundAccount> findByAccountIdForUpdate(@Param("accountId") String accountId);
}
