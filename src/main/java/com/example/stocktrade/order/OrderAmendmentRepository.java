package com.example.stocktrade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderAmendmentRepository extends JpaRepository<OrderAmendment, String> {

    Optional<OrderAmendment> findByAmendId(String amendId);

    long countByOrderId(String orderId);
}
