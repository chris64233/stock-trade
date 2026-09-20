package com.example.stocktrade.order;

import com.example.stocktrade.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OrderService {

    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    private static final int MAX_ACCOUNT_ID_LENGTH = 64;
    private static final int MAX_SYMBOL_LENGTH = 10;
    private static final int MAX_EXECUTION_ID_LENGTH = 64;
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final StockOrderRepository repository;
    private final ExecutionReportRepository executionReportRepository;

    public OrderService(StockOrderRepository repository, ExecutionReportRepository executionReportRepository) {
        this.repository = repository;
        this.executionReportRepository = executionReportRepository;
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

    @Transactional(readOnly = true)
    public Page<StockOrder> query(String accountId, String symbol, String status, String side,
                                  Integer page, Integer size) {
        String normalizedAccountId = normalize(accountId, "accountId", MAX_ACCOUNT_ID_LENGTH, false);
        String normalizedSymbol = symbol == null ? null : normalize(symbol, "symbol", MAX_SYMBOL_LENGTH, true);
        OrderStatus statusFilter = parseEnum(OrderStatus.class, status, "status");
        OrderSide sideFilter = parseEnum(OrderSide.class, side, "side");

        int pageNumber = page == null ? DEFAULT_PAGE : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "page 不能小于 0");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }

        Specification<StockOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("accountId"), normalizedAccountId));
            if (normalizedSymbol != null) {
                predicates.add(cb.equal(root.get("symbol"), normalizedSymbol));
            }
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (sideFilter != null) {
                predicates.add(cb.equal(root.get("side"), sideFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return repository.findAll(spec, pageable);
    }

    @Transactional
    public StockOrder cancel(String id) {
        StockOrder order = getById(id);
        if (order.getStatus() == OrderStatus.FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE", "委托已完全成交，不能撤单");
        }
        order.cancel();
        return repository.save(order);
    }

    @Transactional
    public RegisterExecutionResult registerExecution(String orderId, RegisterExecutionRequest request) {
        String executionId = normalize(request.executionId(), "executionId", MAX_EXECUTION_ID_LENGTH, false);
        StockOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));

        var existing = executionReportRepository.findByExecutionId(executionId);
        if (existing.isPresent()) {
            ExecutionReport report = existing.get();
            if (report.matches(order.getId(), request.quantity(), request.price())) {
                return new RegisterExecutionResult(report, order, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT",
                    "executionId 已存在且请求字段不一致");
        }

        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_FILLABLE",
                    "当前状态的委托不能接收成交回报");
        }
        if (request.quantity() > order.getRemainingQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "FILL_QUANTITY_EXCEEDED",
                    "成交数量超过委托剩余数量");
        }

        ExecutionReport report = new ExecutionReport(executionId, order.getId(),
                request.quantity(), request.price());
        try {
            executionReportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT",
                    "executionId 已存在且请求字段不一致");
        }
        order.applyFill(request.quantity());
        repository.save(order);
        return new RegisterExecutionResult(report, order, true);
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

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 取值非法");
        }
    }

    public record CreateOrderResult(StockOrder order, boolean created) {
    }

    public record RegisterExecutionResult(ExecutionReport report, StockOrder order, boolean created) {
    }
}
