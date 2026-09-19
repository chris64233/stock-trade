package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockOrderRepository extends JpaRepository<StockOrder, String> {

    Optional<StockOrder> findByClientOrderId(String clientOrderId);
}
