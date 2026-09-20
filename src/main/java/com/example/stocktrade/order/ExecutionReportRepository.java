package com.example.stocktrade.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionReportRepository extends JpaRepository<ExecutionReport, String> {

    Optional<ExecutionReport> findByExecutionId(String executionId);

    Page<ExecutionReport> findByOrderId(String orderId, Pageable pageable);

    List<ExecutionReport> findByOrderId(String orderId);
}
