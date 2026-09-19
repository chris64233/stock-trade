package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeFillRepository extends JpaRepository<TradeFill, String> {

    Optional<TradeFill> findByExecutionId(String executionId);
}
