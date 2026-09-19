package com.example.stocktrade.order;

import com.example.stocktrade.common.ConflictException;
import com.example.stocktrade.common.ResourceNotFoundException;
import com.example.stocktrade.order.dto.CreateOrderRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final StockOrderRepository repository;

    public OrderService(StockOrderRepository repository) {
        this.repository = repository;
    }

    public record CreateResult(StockOrder order, boolean created) {
    }

    @Transactional
    public CreateResult create(CreateOrderRequest request) {
        var existing = repository.findByClientOrderId(request.getClientOrderId());
        if (existing.isPresent()) {
            return resolveDuplicate(existing.get(), request);
        }
        StockOrder order = new StockOrder(
                request.getClientOrderId(),
                request.getAccountId(),
                request.getSymbol(),
                request.getSide(),
                request.getQuantity(),
                request.getLimitPrice()
        );
        try {
            return new CreateResult(repository.saveAndFlush(order), true);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束兜底：重新读取后按相同规则判定
            return repository.findByClientOrderId(request.getClientOrderId())
                    .map(found -> resolveDuplicate(found, request))
                    .orElseThrow(() -> e);
        }
    }

    private CreateResult resolveDuplicate(StockOrder existing, CreateOrderRequest request) {
        boolean same = existing.matches(
                request.getAccountId(),
                request.getSymbol(),
                request.getSide(),
                request.getQuantity(),
                request.getLimitPrice()
        );
        if (!same) {
            throw new ConflictException("clientOrderId 已存在且字段不一致");
        }
        return new CreateResult(existing, false);
    }

    @Transactional(readOnly = true)
    public StockOrder getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("委托不存在: " + id));
    }

    @Transactional
    public StockOrder cancel(String id) {
        StockOrder order = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("委托不存在: " + id));
        order.cancel();
        return repository.save(order);
    }
}
