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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OrderService {

    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    private static final int MAX_ACCOUNT_ID_LENGTH = 64;
    private static final int MAX_SYMBOL_LENGTH = 10;
    private static final int MAX_EXECUTION_ID_LENGTH = 64;
    private static final int MAX_AMENDMENT_ID_LENGTH = 64;

    private final StockOrderRepository repository;
    private final ExecutionReportRepository executionReportRepository;
    private final OrderAmendmentRepository amendmentRepository;

    public OrderService(StockOrderRepository repository, ExecutionReportRepository executionReportRepository,
                        OrderAmendmentRepository amendmentRepository) {
        this.repository = repository;
        this.executionReportRepository = executionReportRepository;
        this.amendmentRepository = amendmentRepository;
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
    public ExecutionSummaryResponse getExecutionSummary(String orderId) {
        StockOrder order = repository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
        List<ExecutionReport> executions = executionReportRepository.findByOrderId(orderId);

        long filledQuantity = 0;
        BigDecimal totalExecutedAmount = BigDecimal.ZERO;
        Instant lastExecutedAt = null;
        for (ExecutionReport execution : executions) {
            filledQuantity += execution.getQuantity();
            totalExecutedAmount = totalExecutedAmount.add(
                    execution.getPrice().multiply(BigDecimal.valueOf(execution.getQuantity())));
            if (lastExecutedAt == null || execution.getExecutedAt().isAfter(lastExecutedAt)) {
                lastExecutedAt = execution.getExecutedAt();
            }
        }
        totalExecutedAmount = totalExecutedAmount.setScale(4, RoundingMode.HALF_UP);
        BigDecimal averageExecutionPrice = filledQuantity == 0 ? null
                : totalExecutedAmount.divide(BigDecimal.valueOf(filledQuantity), 4, RoundingMode.HALF_UP);

        return new ExecutionSummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getQuantity(),
                filledQuantity,
                order.getQuantity() - filledQuantity,
                executions.size(),
                totalExecutedAmount,
                averageExecutionPrice,
                lastExecutedAt
        );
    }

    @Transactional(readOnly = true)
    public OrderExecutionsResult listExecutions(String orderId, String page, String size) {
        StockOrder order = repository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
        int pageNumber = parsePage(page == null ? "0" : page, "page", 0, Integer.MAX_VALUE);
        int pageSize = parsePage(size == null ? "20" : size, "size", 1, 100);
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.asc("executedAt"), Sort.Order.asc("id")));
        Page<ExecutionReport> executions = executionReportRepository.findByOrderId(orderId, pageable);
        return new OrderExecutionsResult(order, executions);
    }

    @Transactional(readOnly = true)
    public Page<StockOrder> search(String accountId, String symbol, String status, String side,
                                   String page, String size) {
        String normalizedAccountId = normalize(accountId, "accountId", MAX_ACCOUNT_ID_LENGTH, false);
        String normalizedSymbol = symbol == null ? null
                : normalize(symbol, "symbol", MAX_SYMBOL_LENGTH, true);
        OrderStatus statusFilter = parseEnum(status, OrderStatus.class, "status");
        OrderSide sideFilter = parseEnum(side, OrderSide.class, "side");
        int pageNumber = parsePage(page, "page", 0, Integer.MAX_VALUE);
        int pageSize = parsePage(size, "size", 1, 100);

        Specification<StockOrder> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
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
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return repository.findAll(spec, pageable);
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 不能为空");
        }
        try {
            return Enum.valueOf(enumType, trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 取值非法");
        }
    }

    private int parsePage(String value, String field, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalidPage(field, min, max);
        }
        if (parsed < min || parsed > max) {
            throw invalidPage(field, min, max);
        }
        return parsed;
    }

    private ApiException invalidPage(String field, int min, int max) {
        String range = max == Integer.MAX_VALUE ? "不小于 " + min + " 的整数"
                : min + " 到 " + max + " 之间的整数";
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 必须是" + range);
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

    @Transactional
    public AmendOrderResult amend(String orderId, AmendOrderRequest request) {
        String amendmentId = normalize(request.amendmentId(), "amendmentId", MAX_AMENDMENT_ID_LENGTH, false);
        StockOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));

        var existing = amendmentRepository.findByAmendmentId(amendmentId);
        if (existing.isPresent()) {
            OrderAmendment amendment = existing.get();
            if (amendment.matches(order.getId(), request.quantity(), request.limitPrice())) {
                OrderStatus status = amendment.getFilledQuantity() == 0 ? OrderStatus.OPEN : OrderStatus.PARTIALLY_FILLED;
                return new AmendOrderResult(amendment, status, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "AMENDMENT_ID_CONFLICT",
                    "amendmentId 已存在且请求字段不一致");
        }

        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_AMENDABLE",
                    "当前状态的委托不能改单");
        }
        if (request.quantity() <= order.getFilledQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "AMEND_QUANTITY_INVALID",
                    "改单后的总数量必须严格大于已成交数量");
        }

        OrderAmendment amendment = new OrderAmendment(amendmentId, order.getId(),
                order.getQuantity(), request.quantity(), order.getLimitPrice(), request.limitPrice(),
                order.getFilledQuantity());
        try {
            amendmentRepository.saveAndFlush(amendment);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AMENDMENT_ID_CONFLICT",
                    "amendmentId 已存在且请求字段不一致");
        }
        order.amend(request.quantity(), request.limitPrice());
        repository.saveAndFlush(order);
        return new AmendOrderResult(amendment, order.getStatus(), true);
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

    public record RegisterExecutionResult(ExecutionReport report, StockOrder order, boolean created) {
    }

    public record OrderExecutionsResult(StockOrder order, Page<ExecutionReport> executions) {
    }

    public record AmendOrderResult(OrderAmendment amendment, OrderStatus status, boolean created) {
    }
}
