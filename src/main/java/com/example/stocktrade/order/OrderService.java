package com.example.stocktrade.order;

import com.example.stocktrade.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class OrderService {

    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    private static final int MAX_ACCOUNT_ID_LENGTH = 64;
    private static final int MAX_SYMBOL_LENGTH = 10;
    private static final int MAX_EXECUTION_ID_LENGTH = 64;

    private final StockOrderRepository repository;
    private final TradeFillRepository tradeFillRepository;

    public OrderService(StockOrderRepository repository, TradeFillRepository tradeFillRepository) {
        this.repository = repository;
        this.tradeFillRepository = tradeFillRepository;
    }

    public CreateOrderResult create(CreateOrderRequest request) {
        String clientOrderId = normalize(request.clientOrderId(), "clientOrderId", MAX_CLIENT_ORDER_ID_LENGTH, false);
        String accountId = normalize(request.accountId(), "accountId", MAX_ACCOUNT_ID_LENGTH, false);
        String symbol = normalize(request.symbol(), "symbol", MAX_SYMBOL_LENGTH, true);

        StockOrder order = new StockOrder(clientOrderId, accountId, symbol,
                request.side(), request.quantity(), request.limitPrice());
        try {
            return new CreateOrderResult(repository.saveAndFlush(order), true);
        } catch (DataIntegrityViolationException e) {
            StockOrder existing = repository.findByClientOrderId(clientOrderId)
                    .orElseThrow(() -> e);
            return resolveDuplicate(existing, request, accountId, symbol);
        }
    }

    private CreateOrderResult resolveDuplicate(StockOrder existing, CreateOrderRequest request,
                                               String accountId, String symbol) {
        boolean identical = existing.getAccountId().equals(accountId)
                && existing.getSymbol().equals(symbol)
                && existing.getSide() == request.side()
                && existing.getQuantity() == request.quantity()
                && existing.getLimitPrice().compareTo(request.limitPrice()) == 0;
        if (!identical) {
            throw new ApiException(HttpStatus.CONFLICT, "CLIENT_ORDER_ID_CONFLICT",
                    "clientOrderId 已存在且请求字段不一致");
        }
        return new CreateOrderResult(existing, false);
    }

    @Transactional(readOnly = true)
    public StockOrder getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
    }

    @Transactional
    public StockOrder cancel(String id) {
        StockOrder order = getById(id);
        if (order.getStatus() == OrderStatus.FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE", "已完全成交的委托不可撤单");
        }
        order.cancel();
        return repository.save(order);
    }

    @Transactional
    public RegisterFillResult registerFill(String orderId, RegisterFillRequest request) {
        StockOrder order = repository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
        String executionId = normalize(request.executionId(), "executionId", MAX_EXECUTION_ID_LENGTH, false);

        TradeFill existing = tradeFillRepository.findByExecutionId(executionId).orElse(null);
        if (existing != null) {
            return new RegisterFillResult(resolveDuplicateFill(existing, order, request), order, false);
        }

        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_FILLABLE",
                    "当前状态的委托不能接收成交回报");
        }
        if (request.quantity() > order.getRemainingQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "FILL_QUANTITY_EXCEEDED",
                    "成交数量超过委托剩余数量");
        }

        TradeFill fill = new TradeFill(executionId, order.getId(), request.quantity(), request.price());
        order.applyFill(request.quantity());
        try {
            TradeFill saved = tradeFillRepository.saveAndFlush(fill);
            return new RegisterFillResult(saved, repository.save(order), true);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT",
                    "executionId 已存在且请求字段不一致");
        }
    }

    private TradeFill resolveDuplicateFill(TradeFill existing, StockOrder order, RegisterFillRequest request) {
        boolean identical = existing.getOrderId().equals(order.getId())
                && existing.getQuantity() == request.quantity()
                && existing.getPrice().compareTo(request.price()) == 0;
        if (!identical) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT",
                    "executionId 已存在且请求字段不一致");
        }
        return existing;
    }

    private String normalize(String value, String field, int maxLength, boolean upperCase) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 去除首尾空白后不能为空");
        }
        String normalized = upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
        if (normalized.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    field + " 最长 " + maxLength + " 个字符");
        }
        return normalized;
    }

    public record CreateOrderResult(StockOrder order, boolean created) {
    }

    public record RegisterFillResult(TradeFill fill, StockOrder order, boolean created) {
    }
}
