package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementReversalRepository extends JpaRepository<SettlementReversal, String> {

    Optional<SettlementReversal> findByReversalId(String reversalId);

    Optional<SettlementReversal> findBySettlementId(String settlementId);

    List<SettlementReversal> findByExecutionIdOrderByReversedAtAscIdAsc(String executionId);
}
