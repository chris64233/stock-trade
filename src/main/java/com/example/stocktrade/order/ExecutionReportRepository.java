package com.example.stocktrade.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExecutionReportRepository extends JpaRepository<ExecutionReport, String> {

    Optional<ExecutionReport> findByExecutionId(String executionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ExecutionReport e where e.executionId = :executionId")
    Optional<ExecutionReport> findByExecutionIdForUpdate(@Param("executionId") String executionId);

    Page<ExecutionReport> findByOrderId(String orderId, Pageable pageable);

    List<ExecutionReport> findByOrderId(String orderId);
}
