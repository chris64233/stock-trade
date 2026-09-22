package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionReversalRepository extends JpaRepository<ExecutionReversal, String> {

    Optional<ExecutionReversal> findByReversalId(String reversalId);

    Optional<ExecutionReversal> findByExecutionId(String executionId);

    long countByOrderId(String orderId);
}
