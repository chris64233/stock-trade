package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementFundMovementRepository extends JpaRepository<SettlementFundMovement, String> {

    Optional<SettlementFundMovement> findBySettlementIdAndType(String settlementId, FundMovementType type);

    List<SettlementFundMovement> findByExecutionIdOrderByCreatedAtAscIdAsc(String executionId);
}
