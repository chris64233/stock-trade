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

import java.time.Duration;
import java.time.Instant;
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
class ExecutionSettlementApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String createOrder(int quantity) throws Exception {
        String json = """
                {
                  "clientOrderId": "co-%s",
                  "accountId": "acc-1",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "quantity": %d,
                  "limitPrice": "150.25"
                }
                """.formatted(UUID.randomUUID(), quantity);
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

    private String settlementJson(Object settlementId, Object executionId) {
        return """
                {
                  "settlementId": %s,
                  "executionId": %s
                }
                """.formatted(
                settlementId == null ? "null" : "\"" + settlementId + "\"",
                executionId == null ? "null" : "\"" + executionId + "\"");
    }

    private MvcResult settle(String orderId, String json) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult reverse(String orderId, String reversalId, String executionId) throws Exception {
        String json = """
                {
                  "reversalId": "%s",
                  "executionId": "%s"
                }
                """.formatted(reversalId, executionId);
        return mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private JsonNode getOrder(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getSummary(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getExecutions(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}/executions", orderId)
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void settlingExecutionConfirmsItWithoutChangingOrder() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");

        MvcResult result = settle(orderId, settlementJson("  " + uniqueId("st") + "  ", executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("settlementId").asText()).startsWith("st-");
        assertThat(body.get("executionId").asText()).isEqualTo(executionId);
        assertThat(body.get("orderId").asText()).isEqualTo(orderId);
        assertThat(body.get("quantity").asLong()).isEqualTo(40);
        assertThat(body.get("price").asDouble()).isEqualTo(150.25);
        assertThat(body.get("settledAt").asText()).isNotEmpty();
        assertThat(body.get("settled").asBoolean()).isTrue();
        assertThat(body.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(60);

        // 结算不改变委托的已成交数量、剩余数量和状态
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(60);

        // 成交汇总仍计入已结算的成交
        JsonNode summary = getSummary(orderId);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
    }

    @Test
    void executionDetailShowsSettlementStatusAndAuditInfo() throws Exception {
        String orderId = createOrder(100);
        String settledExecution = registerExecution(orderId, 30, "150.10");
        String openExecution = registerExecution(orderId, 20, "150.30");
        String settlementId = uniqueId("st");

        assertThat(settle(orderId, settlementJson(settlementId, settledExecution)).getResponse().getStatus())
                .isEqualTo(201);

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        boolean foundSettled = false;
        boolean foundUnsettled = false;
        for (JsonNode item : page.get("content")) {
            if (item.get("executionId").asText().equals(settledExecution)) {
                assertThat(item.get("settled").asBoolean()).isTrue();
                assertThat(item.get("settledAt").asText()).isNotEmpty();
                assertThat(item.get("settlementId").asText()).isEqualTo(settlementId);
                assertThat(item.get("reversed").asBoolean()).isFalse();
                foundSettled = true;
            } else if (item.get("executionId").asText().equals(openExecution)) {
                assertThat(item.get("settled").asBoolean()).isFalse();
                assertThat(item.get("settledAt").isNull()).isTrue();
                assertThat(item.get("settlementId").isNull()).isTrue();
                foundUnsettled = true;
            }
        }
        assertThat(foundSettled).isTrue();
        assertThat(foundUnsettled).isTrue();
    }

    @Test
    void settlingFullyFilledOrderKeepsFilledStatus() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 100, "150.00");
        assertThat(getOrder(orderId).get("status").asText()).isEqualTo("FILLED");

        MvcResult result = settle(orderId, settlementJson(uniqueId("st"), executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderStatus").asText()).isEqualTo("FILLED");
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(100);
        assertThat(body.get("remainingQuantity").asLong()).isZero();

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void settlingReversedExecutionReturns409WithoutChanges() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        assertThat(reverse(orderId, uniqueId("rv"), executionId).getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_REVERSED"));

        JsonNode page = getExecutions(orderId);
        JsonNode item = page.get("content").get(0);
        assertThat(item.get("reversed").asBoolean()).isTrue();
        assertThat(item.get("settled").asBoolean()).isFalse();
        assertThat(item.get("settlementId").isNull()).isTrue();
    }

    @Test
    void reversingSettledExecutionReturns409WithoutChanges() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        assertThat(settle(orderId, settlementJson(uniqueId("st"), executionId)).getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reversalId": "%s",
                                  "executionId": "%s"
                                }
                                """.formatted(uniqueId("rv"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_SETTLED"));

        // 撤销失败，委托与成交状态保持不变
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);
        JsonNode item = getExecutions(orderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isTrue();
        assertThat(item.get("reversed").asBoolean()).isFalse();
    }

    @Test
    void settlingAlreadySettledExecutionWithNewIdReturns409() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        assertThat(settle(orderId, settlementJson(uniqueId("st"), executionId)).getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_SETTLED"));

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("settled").asBoolean()).isTrue();
    }

    @Test
    void identicalReplayReturnsFirstResultWithoutNewAudit() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");

        MvcResult first = settle(orderId, settlementJson(settlementId, executionId));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult replay = settle(orderId, settlementJson("  " + settlementId + "  ", executionId));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        // 数据库时间精度为微秒，允许 1 微秒以内的舍入误差
        assertThat(Duration.between(
                Instant.parse(firstBody.get("settledAt").asText()),
                Instant.parse(replayBody.get("settledAt").asText())).abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1000));
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(replayBody.get("filledQuantity").asLong()).isEqualTo(40);

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("settlementId").asText()).isEqualTo(settlementId);
    }

    @Test
    void replayReturnsFirstSnapshotEvenAfterSubsequentChanges() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");

        MvcResult first = settle(orderId, settlementJson(settlementId, executionId));
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");

        // 结算后委托继续成交至完全成交
        registerExecution(orderId, 60, "151.00");
        assertThat(getOrder(orderId).get("status").asText()).isEqualTo("FILLED");

        MvcResult replay = settle(orderId, settlementJson(settlementId, executionId));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(replayBody.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(replayBody.get("remainingQuantity").asLong()).isEqualTo(60);
    }

    @Test
    void sameSettlementIdForDifferentExecutionReturns409() throws Exception {
        String orderId = createOrder(100);
        String firstExecution = registerExecution(orderId, 20, "150.25");
        String secondExecution = registerExecution(orderId, 20, "150.75");
        String settlementId = uniqueId("st");

        assertThat(settle(orderId, settlementJson(settlementId, firstExecution)).getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(settlementId, secondExecution)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_ID_CONFLICT"));

        // 第二笔成交仍未结算
        JsonNode page = getExecutions(orderId);
        for (JsonNode item : page.get("content")) {
            if (item.get("executionId").asText().equals(secondExecution)) {
                assertThat(item.get("settled").asBoolean()).isFalse();
                assertThat(item.get("settlementId").isNull()).isTrue();
            }
        }
    }

    @Test
    void settlingUnknownExecutionReturns404() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), uniqueId("missing-ex"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/executions/settlements"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void executionOfAnotherOrderReturns404() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String executionId = registerExecution(otherOrderId, 10, "150.25");

        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), executionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));

        JsonNode item = getExecutions(otherOrderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isFalse();
    }

    @Test
    void settlingWithUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/api/orders/{id}/executions/settlements", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), uniqueId("ex"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void invalidSettlementParametersReturn400() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 10, "150.25");

        List<String> bodies = List.of(
                settlementJson("   ", executionId),
                settlementJson(null, executionId),
                settlementJson(uniqueId("st"), "  "),
                settlementJson(uniqueId("st"), null),
                "{\"settlementId\":\"" + uniqueId("st") + "\"}",
                "{\"executionId\":\"" + executionId + "\"}");
        for (String body : bodies) {
            mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        JsonNode item = getExecutions(orderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isFalse();
    }

    @Test
    void failedSettlementLeavesNoStateOrAuditAndCanRetry() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");

        // 不存在的成交导致 404，成交与审计均无变化
        mockMvc.perform(post("/api/orders/{id}/executions/settlements", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(uniqueId("st"), uniqueId("ex"))))
                .andExpect(status().isNotFound());

        JsonNode item = getExecutions(orderId).get("content").get(0);
        assertThat(item.get("settled").asBoolean()).isFalse();
        assertThat(item.get("settlementId").isNull()).isTrue();

        // 失败后可用新的 settlementId 成功结算同一笔成交
        MvcResult result = settle(orderId, settlementJson(uniqueId("st"), executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(getExecutions(orderId).get("content").get(0).get("settled").asBoolean()).isTrue();
    }

    @Test
    void globalSettlementEndpointsSupportCreateAndReplay() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");

        String globalBody = settlementJson(settlementId, executionId);
        mockMvc.perform(post("/api/executions/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(globalBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionId").value(executionId))
                .andExpect(jsonPath("$.settled").value(true));

        mockMvc.perform(post("/api/executions/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(globalBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value(settlementId));

        // 按成交标识定位的入口
        String otherOrderId = createOrder(50);
        String otherExecution = registerExecution(otherOrderId, 10, "150.25");
        mockMvc.perform(post("/api/executions/{executionId}/settlements", otherExecution)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementId\":\"" + uniqueId("st") + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionId").value(otherExecution))
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_FILLED"));
    }

    @Test
    void concurrentSettlementsOfSameExecutionSucceedOnce() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                final String settlementId = uniqueId("st");
                futures.add(pool.submit(() -> {
                    start.await();
                    return settle(orderId, settlementJson(settlementId, executionId)).getResponse().getStatus();
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

        // 委托状态未被结算改变
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("settled").asBoolean()).isTrue();
    }

    @Test
    void concurrentReplayWithSameSettlementIdProducesSingleAuditRecord() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String settlementId = uniqueId("st");
        int concurrency = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return settle(orderId, settlementJson(settlementId, executionId)).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsOnly(200, 201);
            assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .isEqualTo(concurrency - 1);
        } finally {
            pool.shutdown();
        }

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("settlementId").asText()).isEqualTo(settlementId);
    }

    @Test
    void concurrentSettleAndReverseAreMutuallyExclusive() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> settleFutures = new java.util.ArrayList<>();
        List<Future<Integer>> reverseFutures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                final String settlementId = uniqueId("st");
                final String reversalId = uniqueId("rv");
                settleFutures.add(pool.submit(() -> {
                    start.await();
                    return settle(orderId, settlementJson(settlementId, executionId)).getResponse().getStatus();
                }));
                reverseFutures.add(pool.submit(() -> {
                    start.await();
                    return reverse(orderId, reversalId, executionId).getResponse().getStatus();
                }));
            }
            start.countDown();
            int settleCreated = 0;
            int settleConflict = 0;
            for (Future<Integer> future : settleFutures) {
                int status = future.get();
                if (status == 201) {
                    settleCreated++;
                } else if (status == 409) {
                    settleConflict++;
                } else {
                    throw new AssertionError("意外的状态码: " + status);
                }
            }
            int reverseCreated = 0;
            int reverseConflict = 0;
            for (Future<Integer> future : reverseFutures) {
                int status = future.get();
                if (status == 201) {
                    reverseCreated++;
                } else if (status == 409) {
                    reverseConflict++;
                } else {
                    throw new AssertionError("意外的状态码: " + status);
                }
            }
            // 结算与撤销互斥：有且仅有一方成功一次，失败方全部 409
            assertThat(settleCreated + reverseCreated).isEqualTo(1);
            assertThat(settleConflict + reverseConflict).isEqualTo(2 * concurrency - 1);
        } finally {
            pool.shutdown();
        }

        JsonNode item = getExecutions(orderId).get("content").get(0);
        boolean settled = item.get("settled").asBoolean();
        boolean reversed = item.get("reversed").asBoolean();
        assertThat(settled).isNotEqualTo(reversed);
        if (settled) {
            // 结算胜出：委托保持成交后的状态不变
            JsonNode order = getOrder(orderId);
            assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
            assertThat(order.get("filledQuantity").asLong()).isEqualTo(40);
            assertThat(item.get("settlementId").isNull()).isFalse();
        } else {
            // 撤销胜出：遵守现有撤销规则
            JsonNode order = getOrder(orderId);
            assertThat(order.get("status").asText()).isEqualTo("OPEN");
            assertThat(order.get("filledQuantity").asLong()).isZero();
            assertThat(item.get("settlementId").isNull()).isTrue();
        }
    }
}
