package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionReportRepository extends JpaRepository<ExecutionReport, String> {

    Optional<ExecutionReport> findByExecutionId(String executionId);
}
