package com.example.stocktrade.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockOrderRepository extends JpaRepository<StockOrder, String>, JpaSpecificationExecutor<StockOrder> {

    Optional<StockOrder> findByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StockOrder o where o.id = :id")
    Optional<StockOrder> findByIdForUpdate(@Param("id") String id);
}
