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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    private static final int MAX_ACCOUNT_ID_LENGTH = 64;
    private static final int MAX_SYMBOL_LENGTH = 10;
    private static final int MAX_EXECUTION_ID_LENGTH = 64;
    private static final int MAX_AMEND_ID_LENGTH = 64;
    private static final int MAX_REVERSAL_ID_LENGTH = 64;
    private static final int MAX_SETTLEMENT_ID_LENGTH = 64;
    private static final int MAX_REASON_LENGTH = 256;

    private final StockOrderRepository repository;
    private final ExecutionReportRepository executionReportRepository;
    private final OrderAmendmentRepository amendmentRepository;
    private final ExecutionReversalRepository reversalRepository;
    private final ExecutionSettlementRepository settlementRepository;
    private final SettlementReversalRepository settlementReversalRepository;
    private final SettlementFundMovementRepository fundMovementRepository;
    private final FundAccountRepository fundAccountRepository;
    private final FundAccountService fundAccountService;

    public OrderService(StockOrderRepository repository,
                        ExecutionReportRepository executionReportRepository,
                        OrderAmendmentRepository amendmentRepository,
                        ExecutionReversalRepository reversalRepository,
                        ExecutionSettlementRepository settlementRepository,
                        SettlementReversalRepository settlementReversalRepository,
                        SettlementFundMovementRepository fundMovementRepository,
                        FundAccountRepository fundAccountRepository,
                        FundAccountService fundAccountService) {
        this.repository = repository;
        this.executionReportRepository = executionReportRepository;
        this.amendmentRepository = amendmentRepository;
        this.reversalRepository = reversalRepository;
        this.settlementRepository = settlementRepository;
        this.settlementReversalRepository = settlementReversalRepository;
        this.fundMovementRepository = fundMovementRepository;
        this.fundAccountRepository = fundAccountRepository;
        this.fundAccountService = fundAccountService;
    }

    public CreateOrderResult create(CreateOrderRequest request) {
        String clientOrderId = normalize(request.clientOrderId(), "clientOrderId", MAX_CLIENT_ORDER_ID_LENGTH, false);
        String accountId = normalize(request.accountId(), "accountId", MAX_ACCOUNT_ID_LENGTH, false);
        String symbol = normalize(request.symbol(), "symbol", MAX_SYMBOL_LENGTH, true);

        fundAccountService.ensureExists(accountId);

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
        long executionCount = 0;
        for (ExecutionReport execution : executions) {
            if (execution.isReversed()) {
                continue;
            }
            executionCount++;
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
                executionCount,
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
        List<String> executionIds = executions.getContent().stream()
                .map(ExecutionReport::getExecutionId)
                .toList();
        // 同一成交可能存在多条结算（原结算、撤销后重新结算），明细只展示当前生效的一条
        Map<String, ExecutionSettlement> activeSettlementsByExecutionId = settlementRepository
                .findByExecutionIdIn(executionIds).stream()
                .filter(settlement -> !settlement.isReversed())
                .collect(Collectors.toMap(ExecutionSettlement::getExecutionId, settlement -> settlement,
                        (first, second) -> second));
        return new OrderExecutionsResult(order, executions, activeSettlementsByExecutionId);
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
    public AmendOrderResult amend(String orderId, AmendOrderRequest request) {
        String amendId = normalize(request.amendId(), "amendId", MAX_AMEND_ID_LENGTH, false);
        long newQuantity = request.quantity();
        BigDecimal newLimitPrice = request.limitPrice();

        StockOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));

        var existing = amendmentRepository.findByAmendId(amendId);
        if (existing.isPresent()) {
            OrderAmendment amendment = existing.get();
            if (amendment.matches(orderId, newQuantity, newLimitPrice)) {
                return new AmendOrderResult(amendment, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "AMEND_ID_CONFLICT",
                    "amendId 已存在且请求字段不一致");
        }

        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_AMENDABLE",
                    "只有 OPEN 或 PARTIALLY_FILLED 状态的委托可以改单");
        }
        if (newQuantity <= order.getFilledQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "AMEND_QUANTITY_INVALID",
                    "改单后的总数量必须严格大于已成交数量");
        }

        OrderAmendment amendment = new OrderAmendment(amendId, order.getId(),
                order.getQuantity(), newQuantity, order.getLimitPrice(), newLimitPrice,
                order.getFilledQuantity());
        order.amend(newQuantity, newLimitPrice);
        try {
            amendmentRepository.saveAndFlush(amendment);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AMEND_ID_CONFLICT",
                    "amendId 已存在且请求字段不一致");
        }
        repository.save(order);
        return new AmendOrderResult(amendment, true);
    }

    @Transactional
    public RegisterExecutionResult registerExecution(String orderId, RegisterExecutionRequest request) {
        String executionId = normalize(request.executionId(), "executionId", MAX_EXECUTION_ID_LENGTH, false);
        StockOrder order = repository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));

        var existing = executionReportRepository.findByExecutionId(executionId);
        if (existing.isPresent()) {
            ExecutionReport report = existing.get();
            if (report.isReversed()) {
                throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_REVERSED",
                        "该成交标识对应一笔已撤销的成交回报，不能重复登记");
            }
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
    public ReverseExecutionResult reverseExecution(String orderId, String rawReversalId, String rawExecutionId) {
        String reversalId = normalize(rawReversalId, "reversalId", MAX_REVERSAL_ID_LENGTH, false);
        String executionId = normalize(rawExecutionId, "executionId", MAX_EXECUTION_ID_LENGTH, false);

        if (orderId != null
                && repository.findById(orderId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在");
        }

        ExecutionReport report = executionReportRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在"));
        if (orderId != null && !report.getOrderId().equals(orderId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在");
        }

        StockOrder order = repository.findByIdForUpdate(report.getOrderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));

        var existingReversal = reversalRepository.findByReversalId(reversalId);
        if (existingReversal.isPresent()) {
            ExecutionReversal reversal = existingReversal.get();
            if (reversal.getExecutionId().equals(executionId)) {
                return new ReverseExecutionResult(reversal, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_ID_CONFLICT",
                    "reversalId 已存在且对应不同的成交回报");
        }

        if (hasActiveSettlement(executionId)) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_SETTLED",
                    "成交回报已结算，不能撤销");
        }

        if (report.isReversed() || reversalRepository.findByExecutionId(executionId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_REVERSED",
                    "成交回报已被撤销");
        }

        order.reverseFill(report.getQuantity());
        ExecutionReversal reversal = new ExecutionReversal(reversalId, report,
                order.getStatus(), order.getFilledQuantity(), order.getRemainingQuantity());
        report.markReversed(reversal.getReversedAt());
        try {
            reversalRepository.saveAndFlush(reversal);
        } catch (DataIntegrityViolationException e) {
            throw translateReversalConstraintViolation(e);
        }
        executionReportRepository.saveAndFlush(report);
        repository.saveAndFlush(order);
        return new ReverseExecutionResult(reversal, true);
    }

    @Transactional
    public SettleExecutionResult settleExecution(String orderId, String rawSettlementId, String rawExecutionId) {
        String settlementId = normalize(rawSettlementId, "settlementId", MAX_SETTLEMENT_ID_LENGTH, false);
        String executionId = normalize(rawExecutionId, "executionId", MAX_EXECUTION_ID_LENGTH, false);

        if (orderId != null
                && repository.findById(orderId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在");
        }

        ExecutionReport report = executionReportRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在"));
        if (orderId != null && !report.getOrderId().equals(orderId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在");
        }

        // 先锁委托：与成交撤销、结算撤销、重新结算互斥，并发只能形成一个终态
        StockOrder order = repository.findByIdForUpdate(report.getOrderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
        FundAccount fundAccount = getOrCreateFundAccountForUpdate(order.getAccountId());

        var existingSettlement = settlementRepository.findBySettlementId(settlementId);
        if (existingSettlement.isPresent()) {
            ExecutionSettlement settlement = existingSettlement.get();
            if (!settlement.getExecutionId().equals(executionId)) {
                throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ID_CONFLICT",
                        "settlementId 已存在且对应不同的成交回报");
            }
            if (settlement.isReversed()) {
                throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ALREADY_REVERSED",
                        "该结算号对应结算已被撤销，请使用新的结算号重新结算");
            }
            SettlementFundMovement movement = fundMovementRepository
                    .findBySettlementIdAndType(settlementId, FundMovementType.SETTLEMENT)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_ERROR", "结算资金变动缺失"));
            return new SettleExecutionResult(settlement, movement, false);
        }

        if (report.isReversed()) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_REVERSED",
                    "成交回报已被撤销，不能结算");
        }

        List<ExecutionSettlement> chain =
                settlementRepository.findByExecutionIdOrderBySettledAtAscIdAsc(executionId);
        ExecutionSettlement active = chain.stream()
                .filter(settlement -> !settlement.isReversed())
                .findAny()
                .orElse(null);
        if (report.isSettled() || active != null) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_SETTLED",
                    "成交回报已结算");
        }
        // 重新结算回指链上最后一笔被撤销的结算，原结算 -> 撤销 -> 新结算不可变
        String replacesSettlementId = chain.isEmpty()
                ? null
                : chain.get(chain.size() - 1).getSettlementId();

        // 结算只确认成交本身，不改变委托的已成交数量、剩余数量和状态
        ExecutionSettlement settlement = new ExecutionSettlement(settlementId, report, order.getAccountId(),
                replacesSettlementId, order.getStatus(), order.getFilledQuantity(),
                order.getRemainingQuantity());
        try {
            settlementRepository.saveAndFlush(settlement);
        } catch (DataIntegrityViolationException e) {
            throw translateSettlementConstraintViolation(e);
        }

        BigDecimal delta = SettlementFundMovement.signedDelta(report, order.getSide(), false);
        fundAccount.apply(delta);

        SettlementFundMovement movement;
        try {
            movement = fundMovementRepository.saveAndFlush(SettlementFundMovement.settlement(
                    order.getAccountId(), report, settlementId, delta, fundAccount.getBalance()));
        } catch (DataIntegrityViolationException e) {
            throw translateSettlementConstraintViolation(e);
        }

        report.markSettled(settlement.getSettledAt());
        fundAccountRepository.save(fundAccount);
        executionReportRepository.saveAndFlush(report);
        return new SettleExecutionResult(settlement, movement, true);
    }

    @Transactional
    public ReverseSettlementResult reverseSettlement(String orderId, String rawReversalId,
                                                      String rawSettlementId, String rawReason) {
        String reversalId = normalize(rawReversalId, "reversalId", MAX_REVERSAL_ID_LENGTH, false);
        String settlementId = normalize(rawSettlementId, "settlementId", MAX_SETTLEMENT_ID_LENGTH, false);
        String reason = normalize(rawReason, "reason", MAX_REASON_LENGTH, false);

        ExecutionSettlement settlement = settlementRepository.findBySettlementId(settlementId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND", "结算不存在"));
        if (orderId != null && !settlement.getOrderId().equals(orderId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND", "结算不存在");
        }

        // 先锁委托：与结算确认/重新结算互斥，每笔结算只能撤销一次
        StockOrder order = repository.findByIdForUpdate(settlement.getOrderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "委托不存在"));
        FundAccount fundAccount = getOrCreateFundAccountForUpdate(order.getAccountId());

        var existingReversal = settlementReversalRepository.findByReversalId(reversalId);
        if (existingReversal.isPresent()) {
            SettlementReversal reversal = existingReversal.get();
            if (reversal.getSettlementId().equals(settlementId)
                    && reversal.getReason().equals(reason)) {
                SettlementFundMovement movement = fundMovementRepository
                        .findBySettlementIdAndType(settlementId, FundMovementType.REVERSAL)
                        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "INTERNAL_ERROR", "撤销资金变动缺失"));
                return new ReverseSettlementResult(reversal, movement, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_ID_CONFLICT",
                    "reversalId 已存在且对应不同的结算或撤销原因");
        }

        if (settlement.isReversed()) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ALREADY_REVERSED",
                    "结算已被撤销，每笔结算只能撤销一次");
        }

        ExecutionReport report = executionReportRepository.findByExecutionId(settlement.getExecutionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在"));

        SettlementReversal reversal = new SettlementReversal(reversalId, settlement, report,
                order.getAccountId(), reason);
        try {
            settlementReversalRepository.saveAndFlush(reversal);
        } catch (DataIntegrityViolationException e) {
            throw translateSettlementReversalConstraintViolation(e);
        }

        BigDecimal delta = SettlementFundMovement.signedDelta(report, order.getSide(), true);
        fundAccount.apply(delta);

        SettlementFundMovement movement;
        try {
            movement = fundMovementRepository.saveAndFlush(SettlementFundMovement.reversal(
                    order.getAccountId(), report, settlementId, reversalId, delta,
                    fundAccount.getBalance()));
        } catch (DataIntegrityViolationException e) {
            throw translateSettlementReversalConstraintViolation(e);
        }

        // 追加反向记录恢复结算状态：不删除原结算，也不再改变委托成交数量
        settlement.markReversed(reversalId, reversal.getReversedAt());
        report.clearSettlement();
        settlementRepository.saveAndFlush(settlement);
        fundAccountRepository.save(fundAccount);
        executionReportRepository.saveAndFlush(report);
        return new ReverseSettlementResult(reversal, movement, true);
    }

    @Transactional(readOnly = true)
    public FundAccount getFundAccount(String rawAccountId) {
        String accountId = normalize(rawAccountId, "accountId", MAX_ACCOUNT_ID_LENGTH, false);
        return fundAccountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "资金账户不存在"));
    }

    @Transactional(readOnly = true)
    public SettlementChainResult getSettlementChain(String orderId, String rawExecutionId) {
        String executionId = normalize(rawExecutionId, "executionId", MAX_EXECUTION_ID_LENGTH, false);
        ExecutionReport report = executionReportRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在"));
        if (orderId != null && !report.getOrderId().equals(orderId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "成交回报不存在");
        }

        List<ExecutionSettlement> settlements =
                settlementRepository.findByExecutionIdOrderBySettledAtAscIdAsc(executionId);
        List<SettlementReversal> reversals =
                settlementReversalRepository.findByExecutionIdOrderByReversedAtAscIdAsc(executionId);

        List<SettlementChainNode> nodes = new ArrayList<>();
        for (ExecutionSettlement settlement : settlements) {
            SettlementFundMovement movement = fundMovementRepository
                    .findBySettlementIdAndType(settlement.getSettlementId(), FundMovementType.SETTLEMENT)
                    .orElse(null);
            nodes.add(SettlementChainNode.settlement(settlement, movement));
        }
        for (SettlementReversal reversal : reversals) {
            SettlementFundMovement movement = fundMovementRepository
                    .findBySettlementIdAndType(reversal.getSettlementId(), FundMovementType.REVERSAL)
                    .orElse(null);
            nodes.add(SettlementChainNode.reversal(reversal, movement));
        }
        nodes.sort(Comparator.comparing(SettlementChainNode::createdAt));

        String currentSettlementId = settlements.stream()
                .filter(settlement -> !settlement.isReversed())
                .reduce((first, second) -> second)
                .map(ExecutionSettlement::getSettlementId)
                .orElse(null);
        return new SettlementChainResult(executionId, report.getOrderId(),
                currentSettlementId != null, currentSettlementId, nodes);
    }

    /**
     * 资金账户在创建委托时已初始化；此处先加悲观写锁，极端缺失时再走独立事务补建。
     * 固定先锁委托后锁账户，避免死锁。
     */
    private FundAccount getOrCreateFundAccountForUpdate(String accountId) {
        return fundAccountRepository.findByAccountIdForUpdate(accountId)
                .orElseGet(() -> {
                    fundAccountService.ensureExists(accountId);
                    return fundAccountRepository.findByAccountIdForUpdate(accountId)
                            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "INTERNAL_ERROR", "资金账户初始化失败"));
                });
    }

    private boolean hasActiveSettlement(String executionId) {
        return settlementRepository.findByExecutionIdOrderBySettledAtAscIdAsc(executionId).stream()
                .anyMatch(settlement -> !settlement.isReversed());
    }

    private ApiException translateSettlementConstraintViolation(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (message.contains("SETTLEMENT_ID")) {
            return new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ID_CONFLICT",
                    "settlementId 已存在");
        }
        return new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_SETTLED",
                "成交回报已结算");
    }

    private ApiException translateSettlementReversalConstraintViolation(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (message.contains("REVERSAL_ID")) {
            return new ApiException(HttpStatus.CONFLICT, "REVERSAL_ID_CONFLICT",
                    "reversalId 已存在");
        }
        if (message.contains("SETTLEMENT_ID")) {
            return new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ALREADY_REVERSED",
                    "结算已被撤销，每笔结算只能撤销一次");
        }
        return new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_ALREADY_REVERSED",
                "结算已被撤销");
    }

    private ApiException translateReversalConstraintViolation(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (message.contains("REVERSAL_ID")) {
            return new ApiException(HttpStatus.CONFLICT, "REVERSAL_ID_CONFLICT",
                    "reversalId 已存在");
        }
        return new ApiException(HttpStatus.CONFLICT, "EXECUTION_ALREADY_REVERSED",
                "成交回报已被撤销");
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

    public record OrderExecutionsResult(StockOrder order, Page<ExecutionReport> executions,
                                        Map<String, ExecutionSettlement> settlementsByExecutionId) {
    }

    public record AmendOrderResult(OrderAmendment amendment, boolean created) {
    }

    public record ReverseExecutionResult(ExecutionReversal reversal, boolean created) {
    }

    public record SettleExecutionResult(ExecutionSettlement settlement, SettlementFundMovement movement,
                                        boolean created) {
    }

    public record ReverseSettlementResult(SettlementReversal reversal, SettlementFundMovement movement,
                                          boolean created) {
    }

    public record SettlementChainResult(String executionId, String orderId, boolean settled,
                                        String currentSettlementId, List<SettlementChainNode> nodes) {
    }
}
