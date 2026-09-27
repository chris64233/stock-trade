package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExecutionSettlementRepository extends JpaRepository<ExecutionSettlement, String> {

    Optional<ExecutionSettlement> findBySettlementId(String settlementId);

    List<ExecutionSettlement> findByExecutionIdOrderBySettledAtAscIdAsc(String executionId);

    List<ExecutionSettlement> findByExecutionIdIn(Collection<String> executionIds);
}
