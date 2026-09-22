package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionRevocationRepository extends JpaRepository<ExecutionRevocation, String> {

    Optional<ExecutionRevocation> findByRevokeId(String revokeId);

    Optional<ExecutionRevocation> findByExecutionId(String executionId);
}
