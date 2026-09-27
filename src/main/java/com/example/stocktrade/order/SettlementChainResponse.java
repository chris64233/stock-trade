package com.example.stocktrade.order;

import java.util.List;

public record SettlementChainResponse(
        String executionId,
        String orderId,
        boolean settled,
        String currentSettlementId,
        List<SettlementChainNode> chain
) {
    public static SettlementChainResponse from(OrderService.SettlementChainResult result) {
        return new SettlementChainResponse(
                result.executionId(),
                result.orderId(),
                result.settled(),
                result.currentSettlementId(),
                result.nodes()
        );
    }
}
