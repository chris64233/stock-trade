package com.example.stocktrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettlementReversalApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String createOrder(String side, int quantity, String accountId) throws Exception {
        String json = """
                {
                  "clientOrderId": "co-%s",
                  "accountId": "%s",
                  "symbol": "AAPL",
                  "side": "%s",
                  "quantity": %d,
                  "limitPrice": "200.00"
                }
                """.formatted(UUID.randomUUID(), accountId, side, quantity);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String registerExecution(String orderId, long quantity, String price) throws Exception {
        String json = """
                {
                  "executionId": "ex-%s",
                  "quantity": %d,
                  "price": %s
                }
                """.formatted(UUID.randomUUID(), quantity, price);
        MvcResult result = mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("executionId").asText();
    }

    private String settlementJson(String settlementId, String executionId) {
        return """
                {
                  "settlementId": "%s",
                  "executionId": "%s"
                }
                """.formatted(settlementId, executionId);
    }

    private MvcResult settle(String orderId, String settlementId, String executionId) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(settlementId, executionId)))
                .andReturn();
    }

    private String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String settlementReversalJson(String reversalId, String settlementId, String reason) {
        return """
                {
                  "reversalId": %s,
                  "settlementId": %s,
                  "reason": %s
                }
                """.formatted(jsonValue(reversalId), jsonValue(settlementId), jsonValue(reason));
    }

    /** 全局入口：POST /api/executions/settlements/reversals */
    private MvcResult reverseSettlement(String reversalId, String settlementId, String reason) throws Exception {
        return mockMvc.perform(post("/api/executions/settlements/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementReversalJson(reversalId, settlementId, reason)))
                .andReturn();
    }

    /** 按结算号入口：POST /api/executions/settlements/{settlementId}/reversals */
    private MvcResult reverseSettlementBySettlementId(String settlementId, String reversalId, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/executions/settlements/{settlementId}/reversals", settlementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reversalId": "%s",
                                  "reason": "%s"
                                }
                                """.formatted(reversalId, reason)))
                .andReturn();
    }

    /** 按委托+成交入口：撤销该成交当前生效的结算 */
    private MvcResult reverseActiveSettlement(String orderId, String executionId, String reversalId, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/executions/{executionId}/settlement-reversal",
                                orderId, executionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reversalId": "%s",
                                  "reason": "%s"
                                }
                                """.formatted(reversalId, reason)))
                .andReturn();
    }

    private JsonNode getFundAccount(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/fund-accounts/{accountId}", accountId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getChain(String executionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/executions/{executionId}/settlement-chain", executionId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getChain(String orderId, String executionId) throws Exception {
        MvcResult result = mockMvc.perform(get(
                        "/api/orders/{id}/executions/{executionId}/settlement-chain", orderId, executionId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getExecutions(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}/executions", orderId)
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOrder(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void settlingAppendsFundMovementAndUpdatesBalanceInSameTransaction() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");

        MvcResult result = settle(orderId, uniqueId("st"), executionId);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("settlementAmount").asDouble()).isEqualTo(6010.0);
        assertThat(body.get("signedDelta").asDouble()).isEqualTo(6010.0);
        assertThat(body.get("balanceAfter").asDouble()).isEqualTo(6010.0);
        assertThat(body.get("reversed").asBoolean()).isFalse();
        assertThat(body.get("replacesSettlementId").isNull()).isTrue();

        // 资金账户状态同步落账
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(6010.0);

        // 结算不改变委托成交数量与状态
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(60);
    }

    @Test
    void buySettlementPostsNegativeDeltaAndReversalRestoresZero() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("BUY", 100, accountId);
        String executionId = registerExecution(orderId, 20, "10.00");

        JsonNode settled = objectMapper.readTree(
                settle(orderId, uniqueId("st"), executionId).getResponse().getContentAsString());
        assertThat(settled.get("signedDelta").asDouble()).isEqualTo(-200.0);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(-200.0);

        MvcResult reversal = reverseSettlement(uniqueId("rv"), settled.get("settlementId").asText(), "差错更正");
        assertThat(reversal.getResponse().getStatus()).isEqualTo(201);
        JsonNode reversalBody = objectMapper.readTree(reversal.getResponse().getContentAsString());
        assertThat(reversalBody.get("signedDelta").asDouble()).isEqualTo(200.0);
        assertThat(reversalBody.get("balanceAfter").asDouble()).isEqualTo(0.0);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(0.0);
    }

    @Test
    void reversingSettlementAppendsReverseRecordKeepsOriginalAndDoesNotChangeFillQuantity() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        JsonNode settled = objectMapper.readTree(
                settle(orderId, settlementId, executionId).getResponse().getContentAsString());
        String originalAuditId = settled.get("id").asText();

        MvcResult reversal = reverseSettlement(uniqueId("rv"), settlementId, "对方账号错误");
        assertThat(reversal.getResponse().getStatus()).isEqualTo(201);
        JsonNode reversalBody = objectMapper.readTree(reversal.getResponse().getContentAsString());
        assertThat(reversalBody.get("settlementId").asText()).isEqualTo(settlementId);
        assertThat(reversalBody.get("executionId").asText()).isEqualTo(executionId);
        assertThat(reversalBody.get("orderId").asText()).isEqualTo(orderId);
        assertThat(reversalBody.get("accountId").asText()).isEqualTo(accountId);
        assertThat(reversalBody.get("reason").asText()).isEqualTo("对方账号错误");
        assertThat(reversalBody.get("signedDelta").asDouble()).isEqualTo(-6010.0);
        assertThat(reversalBody.get("balanceAfter").asDouble()).isEqualTo(0.0);

        // 资金状态恢复
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(0.0);

        // 原结算不删除：链上原结算仍在但已置 reversed，撤销记录紧随其后
        JsonNode chain = getChain(executionId);
        assertThat(chain.get("settled").asBoolean()).isFalse();
        assertThat(chain.get("currentSettlementId").isNull()).isTrue();
        assertThat(chain.get("chain")).hasSize(2);
        JsonNode originalNode = chain.get("chain").get(0);
        assertThat(originalNode.get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(originalNode.get("settlementId").asText()).isEqualTo(settlementId);
        assertThat(originalNode.get("reversed").asBoolean()).isTrue();
        JsonNode reversalNode = chain.get("chain").get(1);
        assertThat(reversalNode.get("type").asText()).isEqualTo("REVERSAL");
        assertThat(reversalNode.get("reversalId").asText())
                .isEqualTo(reversalBody.get("reversalId").asText());
        assertThat(reversalNode.get("reason").asText()).isEqualTo("对方账号错误");

        // 成交回到未结算，但成交本身未被撤销，委托成交数量不变
        JsonNode item = getExecutions(orderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isFalse();
        assertThat(item.get("settlementId").isNull()).isTrue();
        assertThat(item.get("reversed").asBoolean()).isFalse();
        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(60);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");

        // 结算审计记录主键不变，没有被删除重建
        assertThat(getChain(orderId, executionId).get("chain").get(0).get("settlementId").asText())
                .isEqualTo(settlementId);
        assertThat(originalAuditId).isEqualTo(settled.get("id").asText());
    }

    @Test
    void sameReversalIdWithSameReasonIsIdempotentWithoutNewRecordsOrFundChange() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        settle(orderId, settlementId, executionId);
        String reversalId = uniqueId("rv");

        MvcResult first = reverseSettlement("  " + reversalId + "  ", settlementId, "  原因 A  ");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("reason").asText()).isEqualTo("原因 A");

        MvcResult replay = reverseSettlement(reversalId, settlementId, "原因 A");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("reversalId").asText()).isEqualTo(reversalId);
        assertThat(replayBody.get("balanceAfter").asDouble()).isZero();

        // 没有新增撤销记录或资金变动
        assertThat(getChain(executionId).get("chain")).hasSize(2);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isZero();
    }

    @Test
    void sameReversalIdWithDifferentReasonReturns409AndLeavesStateUntouched() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        settle(orderId, settlementId, executionId);
        String reversalId = uniqueId("rv");
        assertThat(reverseSettlement(reversalId, settlementId, "原因 A").getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/executions/settlements/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementReversalJson(reversalId, settlementId, "原因 B")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_ID_CONFLICT"));

        // 资金与链路保持撤销后的状态，未追加记录
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isZero();
        JsonNode chain = getChain(executionId);
        assertThat(chain.get("chain")).hasSize(2);
        assertThat(chain.get("chain").get(1).get("reason").asText()).isEqualTo("原因 A");
    }

    @Test
    void sameReversalIdForDifferentSettlementReturns409() throws Exception {
        String firstAccount = uniqueId("acc");
        String secondAccount = uniqueId("acc");
        String firstOrder = createOrder("SELL", 100, firstAccount);
        String secondOrder = createOrder("SELL", 100, secondAccount);
        String firstExecution = registerExecution(firstOrder, 10, "10.00");
        String secondExecution = registerExecution(secondOrder, 10, "10.00");
        String firstSettlement = uniqueId("st");
        String secondSettlement = uniqueId("st");
        settle(firstOrder, firstSettlement, firstExecution);
        settle(secondOrder, secondSettlement, secondExecution);
        String reversalId = uniqueId("rv");
        assertThat(reverseSettlement(reversalId, firstSettlement, "原因 A").getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/executions/settlements/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementReversalJson(reversalId, secondSettlement, "原因 A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_ID_CONFLICT"));

        // 第二笔结算仍然生效，资金未变
        assertThat(getChain(secondExecution).get("settled").asBoolean()).isTrue();
        assertThat(getFundAccount(secondAccount).get("balance").asDouble()).isEqualTo(100.0);
    }

    @Test
    void eachSettlementCanBeReversedOnlyOnce() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        settle(orderId, settlementId, executionId);
        assertThat(reverseSettlement(uniqueId("rv"), settlementId, "原因 A").getResponse().getStatus())
                .isEqualTo(201);

        // 换一个撤销号再次撤销同一结算仍然被拒绝
        mockMvc.perform(post("/api/executions/settlements/{settlementId}/reversals", settlementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversalId":"%s","reason":"原因 B"}
                                """.formatted(uniqueId("rv"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_ALREADY_REVERSED"));

        assertThat(getChain(executionId).get("chain")).hasSize(2);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isZero();
    }

    @Test
    void oldSettlementIdCannotReplayAfterReversalButNewSettlementIdReSettles() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String originalSettlementId = uniqueId("st");
        settle(orderId, originalSettlementId, executionId);
        reverseSettlement(uniqueId("rv"), originalSettlementId, "原因 A");

        // 原结算号不能再次处理
        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(originalSettlementId, executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_ALREADY_REVERSED"));

        // 用新结算号重新结算
        String newSettlementId = uniqueId("st");
        MvcResult reSettle = settle(orderId, newSettlementId, executionId);
        assertThat(reSettle.getResponse().getStatus()).isEqualTo(201);
        JsonNode reSettled = objectMapper.readTree(reSettle.getResponse().getContentAsString());
        assertThat(reSettled.get("settlementId").asText()).isEqualTo(newSettlementId);
        assertThat(reSettled.get("replacesSettlementId").asText()).isEqualTo(originalSettlementId);
        assertThat(reSettled.get("reversed").asBoolean()).isFalse();
        assertThat(reSettled.get("signedDelta").asDouble()).isEqualTo(6010.0);
        assertThat(reSettled.get("balanceAfter").asDouble()).isEqualTo(6010.0);

        // 原结算、撤销、新结算组成不可变关系链，资金变化链完整可还原
        JsonNode chain = getChain(executionId);
        assertThat(chain.get("settled").asBoolean()).isTrue();
        assertThat(chain.get("currentSettlementId").asText()).isEqualTo(newSettlementId);
        assertThat(chain.get("chain")).hasSize(3);
        assertThat(chain.get("chain").get(0).get("settlementId").asText()).isEqualTo(originalSettlementId);
        assertThat(chain.get("chain").get(0).get("reversed").asBoolean()).isTrue();
        assertThat(chain.get("chain").get(1).get("type").asText()).isEqualTo("REVERSAL");
        JsonNode newNode = chain.get("chain").get(2);
        assertThat(newNode.get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(newNode.get("settlementId").asText()).isEqualTo(newSettlementId);
        assertThat(newNode.get("replacesSettlementId").asText()).isEqualTo(originalSettlementId);
        assertThat(newNode.get("reversed").asBoolean()).isFalse();

        // 明细展示当前生效的新结算
        JsonNode item = getExecutions(orderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isTrue();
        assertThat(item.get("settlementId").asText()).isEqualTo(newSettlementId);
    }

    @Test
    void chainCanContinueAcrossRepeatedReversalAndReSettlement() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String st1 = uniqueId("st");
        settle(orderId, st1, executionId);
        reverseSettlement(uniqueId("rv"), st1, "第一次撤销");
        String st2 = uniqueId("st");
        settle(orderId, st2, executionId);
        reverseSettlementBySettlementId(st2, uniqueId("rv"), "第二次撤销");
        String st3 = uniqueId("st");
        settle(orderId, st3, executionId);

        JsonNode chain = getChain(executionId);
        assertThat(chain.get("currentSettlementId").asText()).isEqualTo(st3);
        assertThat(chain.get("settled").asBoolean()).isTrue();
        assertThat(chain.get("chain")).hasSize(5);
        List<String> types = new ArrayList<>();
        List<Double> deltas = new ArrayList<>();
        for (JsonNode node : chain.get("chain")) {
            types.add(node.get("type").asText());
            deltas.add(node.get("signedDelta").asDouble());
        }
        assertThat(types).containsExactly("SETTLEMENT", "REVERSAL", "SETTLEMENT", "REVERSAL", "SETTLEMENT");
        assertThat(deltas).containsExactly(6010.0, -6010.0, 6010.0, -6010.0, 6010.0);
        assertThat(chain.get("chain").get(2).get("replacesSettlementId").asText()).isEqualTo(st1);
        assertThat(chain.get("chain").get(4).get("replacesSettlementId").asText()).isEqualTo(st2);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(6010.0);
    }

    @Test
    void reversingByExecutionEndpointReversesActiveSettlementAndAllowsExecutionReversalAfterwards()
            throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        settle(orderId, uniqueId("st"), executionId);

        // 结算生效时成交回报不能撤销
        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversalId":"%s","executionId":"%s"}
                                """.formatted(uniqueId("rv"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_SETTLED"));

        // 按成交入口撤销当前生效的结算
        MvcResult result = reverseActiveSettlement(orderId, executionId, uniqueId("rv"), "按成交撤销");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isZero();

        // 结算撤销后没有生效结算，成交回报撤销恢复可用（委托成交数量被扣减）
        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reversalId":"%s","executionId":"%s"}
                                """.formatted(uniqueId("rv"), executionId)))
                .andExpect(status().isCreated());
        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("status").asText()).isEqualTo("OPEN");

        // 成交回报撤销后不能重新结算
        assertThat(settle(orderId, uniqueId("st"), executionId).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void reversingByExecutionWithoutActiveSettlementReturns409() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");

        assertThat(reverseActiveSettlement(orderId, executionId, uniqueId("rv"), "无结算可撤")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reversingUnknownSettlementReturns404() throws Exception {
        mockMvc.perform(post("/api/executions/settlements/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementReversalJson(uniqueId("rv"), uniqueId("missing-st"), "原因")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
    }

    @Test
    void reversalUnderAnotherOrderReturns404() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String otherOrderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(otherOrderId, 10, "10.00");
        String settlementId = uniqueId("st");
        settle(otherOrderId, settlementId, executionId);

        mockMvc.perform(post("/api/orders/{id}/executions/settlements/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementReversalJson(uniqueId("rv"), settlementId, "原因")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));

        // 原结算仍生效
        assertThat(getChain(executionId).get("settled").asBoolean()).isTrue();
    }

    @Test
    void invalidReversalParametersReturn400() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 10, "10.00");
        String settlementId = uniqueId("st");
        settle(orderId, settlementId, executionId);

        List<String> bodies = List.of(
                settlementReversalJson("   ", settlementId, "原因"),
                settlementReversalJson(null, settlementId, "原因"),
                settlementReversalJson(uniqueId("rv"), "   ", "原因"),
                settlementReversalJson(uniqueId("rv"), null, "原因"),
                settlementReversalJson(uniqueId("rv"), settlementId, "   "),
                settlementReversalJson(uniqueId("rv"), settlementId, null),
                "{\"reversalId\":\"" + uniqueId("rv") + "\",\"settlementId\":\"" + settlementId + "\"}",
                "{\"settlementId\":\"" + settlementId + "\",\"reason\":\"原因\"}");
        for (String body : bodies) {
            mockMvc.perform(post("/api/executions/settlements/reversals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        // 参数非法不得产生任何状态变化：结算仍生效，资金不变，无撤销记录
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isEqualTo(100.0);
        JsonNode chain = getChain(executionId);
        assertThat(chain.get("settled").asBoolean()).isTrue();
        assertThat(chain.get("chain")).hasSize(1);
        assertThat(getExecutions(orderId).get("content").get(0).get("settled").asBoolean()).isTrue();
    }

    @Test
    void concurrentReversalsOfSameSettlementProduceSingleTerminalState() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        settle(orderId, settlementId, executionId);

        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                final String reversalId = uniqueId("rv");
                futures.add(pool.submit(() -> {
                    start.await();
                    return reverseSettlement(reversalId, settlementId, "并发撤销").getResponse().getStatus();
                }));
            }
            start.countDown();
            int created = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int status = future.get();
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflict++;
                } else {
                    throw new AssertionError("意外的状态码: " + status);
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflict).isEqualTo(concurrency - 1);
        } finally {
            pool.shutdown();
        }

        // 只有一笔撤销、一笔反向资金变动，资金恰好恢复一次
        assertThat(getFundAccount(accountId).get("balance").asDouble()).isZero();
        JsonNode chain = getChain(executionId);
        assertThat(chain.get("settled").asBoolean()).isFalse();
        long reversalNodes = chain.get("chain").findValuesAsText("type").stream()
                .filter("REVERSAL"::equals).count();
        assertThat(reversalNodes).isEqualTo(1);
    }

    @Test
    void concurrentSettlementConfirmAndReversalOfSameExecutionReachSingleTerminalState() throws Exception {
        String accountId = uniqueId("acc");
        String orderId = createOrder("SELL", 100, accountId);
        String executionId = registerExecution(orderId, 40, "150.25");
        String originalSettlementId = uniqueId("st");
        settle(orderId, originalSettlementId, executionId);

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            // 一半撤销原结算，一半用各自新结算号重新结算
            for (int i = 0; i < workers; i++) {
                final boolean reverse = i % 2 == 0;
                final String id = uniqueId(reverse ? "rv" : "st");
                futures.add(pool.submit(() -> {
                    start.await();
                    if (reverse) {
                        return reverseSettlement(id, originalSettlementId, "并发竞争").getResponse().getStatus();
                    }
                    return settle(orderId, id, executionId).getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                int status = future.get();
                assertThat(status).isIn(201, 409);
            }
        } finally {
            pool.shutdown();
        }

        // 终态唯一：原结算至多被撤销一次，至多一笔结算生效，资金只可能是 0 或一笔结算金额
        JsonNode chain = getChain(executionId);
        long reversalNodes = chain.get("chain").findValuesAsText("type").stream()
                .filter("REVERSAL"::equals).count();
        long active = 0;
        for (JsonNode node : chain.get("chain")) {
            if (node.get("type").asText().equals("SETTLEMENT") && !node.get("reversed").asBoolean()) {
                active++;
            }
        }
        assertThat(active).isBetween(0L, 1L);
        double balance = getFundAccount(accountId).get("balance").asDouble();
        assertThat(balance).isIn(0.0, 6010.0);
        if (balance == 0.0) {
            assertThat(chain.get("settled").asBoolean()).isFalse();
            assertThat(active).isZero();
        } else {
            assertThat(chain.get("settled").asBoolean()).isTrue();
            assertThat(active).isEqualTo(1);
        }
        assertThat(reversalNodes).isLessThanOrEqualTo(1);
    }
}
