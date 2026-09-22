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
class ExecutionReversalApiTests {

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

    private String reversalJson(Object reversalId, Object executionId) {
        return """
                {
                  "reversalId": %s,
                  "executionId": %s
                }
                """.formatted(
                reversalId == null ? "null" : "\"" + reversalId + "\"",
                executionId == null ? "null" : "\"" + executionId + "\"");
    }

    private MvcResult reverse(String orderId, String json) throws Exception {
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
    void reversingPartialFillReopensOrderAndDeductsFilledQuantity() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");

        MvcResult result = reverse(orderId, reversalJson("  " + uniqueId("rv") + "  ", executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("reversalId").asText()).startsWith("rv-");
        assertThat(body.get("executionId").asText()).isEqualTo(executionId);
        assertThat(body.get("orderId").asText()).isEqualTo(orderId);
        assertThat(body.get("quantity").asLong()).isEqualTo(40);
        assertThat(body.get("price").asDouble()).isEqualTo(150.25);
        assertThat(body.get("reversedAt").asText()).isNotEmpty();
        assertThat(body.get("reversed").asBoolean()).isTrue();
        assertThat(body.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void reversingOneOfMultipleFillsKeepsPartiallyFilledAndSummaryExcludesReversed() throws Exception {
        String orderId = createOrder(100);
        String firstExecution = registerExecution(orderId, 30, "150.10");
        registerExecution(orderId, 30, "150.30");

        MvcResult result = reverse(orderId, reversalJson(uniqueId("rv"), firstExecution));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(70);

        JsonNode summary = getSummary(orderId);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(70);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        assertThat(summary.get("totalExecutedAmount").asDouble()).isEqualTo(4509.0000);
        assertThat(summary.get("averageExecutionPrice").asDouble()).isEqualTo(150.3000);

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        JsonNode items = page.get("content");
        boolean foundReversed = false;
        boolean foundActive = false;
        for (JsonNode item : items) {
            if (item.get("executionId").asText().equals(firstExecution)) {
                assertThat(item.get("reversed").asBoolean()).isTrue();
                assertThat(item.get("reversedAt").asText()).isNotEmpty();
                foundReversed = true;
            } else {
                assertThat(item.get("reversed").asBoolean()).isFalse();
                assertThat(item.get("reversedAt").isNull());
                foundActive = true;
            }
        }
        assertThat(foundReversed).isTrue();
        assertThat(foundActive).isTrue();
    }

    @Test
    void reversingFullyFilledOrderReopensAndAllowsNewFill() throws Exception {
        String orderId = createOrder(100);
        String firstExecution = registerExecution(orderId, 40, "150.00");
        registerExecution(orderId, 60, "151.00");
        assertThat(getOrder(orderId).get("status").asText()).isEqualTo("FILLED");

        MvcResult result = reverse(orderId, reversalJson(uniqueId("rv"), firstExecution));
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(60);
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(40);

        registerExecution(orderId, 40, "152.00");
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(100);
        assertThat(order.get("remainingQuantity").asLong()).isZero();
    }

    @Test
    void reversingOnlyFillOfFilledOrderReopensFully() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 100, "150.00");

        MvcResult result = reverse(orderId, reversalJson(uniqueId("rv"), executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void reversingOnCancelledOrderKeepsCancelledStatus() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        MvcResult result = reverse(orderId, reversalJson(uniqueId("rv"), executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void reversingAlreadyReversedExecutionReturns409WithoutFurtherChanges() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        assertThat(reverse(orderId, reversalJson(uniqueId("rv"), executionId)).getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(uniqueId("rv"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_REVERSED"));

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("reversed").asBoolean()).isTrue();
    }

    @Test
    void reversingUnknownExecutionReturns404() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(uniqueId("rv"), uniqueId("missing-ex"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/executions/reversals"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        assertThat(getOrder(orderId).get("filledQuantity").asLong()).isZero();
    }

    @Test
    void executionOfAnotherOrderReturns404() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String executionId = registerExecution(otherOrderId, 10, "150.25");

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(uniqueId("rv"), executionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));

        assertThat(getOrder(otherOrderId).get("filledQuantity").asLong()).isEqualTo(10);
    }

    @Test
    void reversingWithUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/api/orders/{id}/executions/reversals", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(uniqueId("rv"), uniqueId("ex"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void invalidReversalParametersReturn400() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 10, "150.25");

        List<String> bodies = List.of(
                reversalJson("   ", executionId),
                reversalJson(null, executionId),
                reversalJson(uniqueId("rv"), "  "),
                reversalJson(uniqueId("rv"), null),
                "{\"reversalId\":\"" + uniqueId("rv") + "\"}",
                "{\"executionId\":\"" + executionId + "\"}");
        for (String body : bodies) {
            mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        assertThat(getOrder(orderId).get("filledQuantity").asLong()).isEqualTo(10);
    }

    @Test
    void reregisteringReversedExecutionIdReturns409AndDoesNotRestoreFill() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        assertThat(reverse(orderId, reversalJson(uniqueId("rv"), executionId)).getResponse().getStatus())
                .isEqualTo(201);

        // 相同字段再次登记仍被拒绝，不能恢复该笔成交
        String json = """
                {
                  "executionId": "%s",
                  "quantity": 40,
                  "price": "150.25"
                }
                """.formatted(executionId);
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_REVERSED"));

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        JsonNode summary = getSummary(orderId);
        assertThat(summary.get("executionCount").asLong()).isZero();
    }

    @Test
    void failedReversalRollsBackOrderAndAudit() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");

        // 不存在的成交导致 404，委托、成交、审计均无变化
        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(uniqueId("rv"), uniqueId("ex"))))
                .andExpect(status().isNotFound());

        assertThat(getOrder(orderId).get("filledQuantity").asLong()).isEqualTo(40);
        JsonNode page = getExecutions(orderId);
        assertThat(page.get("content").get(0).get("reversed").asBoolean()).isFalse();

        // 失败后可用新的 reversalId 成功撤销同一笔成交
        MvcResult result = reverse(orderId, reversalJson(uniqueId("rv"), executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(getOrder(orderId).get("filledQuantity").asLong()).isZero();
    }

    @Test
    void identicalReplayReturnsFirstResultWithoutNewMutationOrAudit() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String reversalId = uniqueId("rv");

        MvcResult first = reverse(orderId, reversalJson(reversalId, executionId));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult replay = reverse(orderId, reversalJson("  " + reversalId + "  ", executionId));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("reversedAt").asText()).isEqualTo(firstBody.get("reversedAt").asText());
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(replayBody.get("filledQuantity").asLong()).isZero();

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("reversed").asBoolean()).isTrue();
    }

    @Test
    void replayReturnsFirstSnapshotEvenAfterNewFillAndCancel() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String reversalId = uniqueId("rv");

        MvcResult first = reverse(orderId, reversalJson(reversalId, executionId));
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("orderStatus").asText()).isEqualTo("OPEN");

        // 撤销后又收到新成交，随后委托被取消
        registerExecution(orderId, 20, "153.00");
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        MvcResult replay = reverse(orderId, reversalJson(reversalId, executionId));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(replayBody.get("filledQuantity").asLong()).isZero();
        assertThat(replayBody.get("remainingQuantity").asLong()).isEqualTo(100);

        // 后续新成交仍计入委托与汇总
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(20);
        assertThat(getSummary(orderId).get("executionCount").asLong()).isEqualTo(1);
    }

    @Test
    void sameReversalIdForDifferentExecutionReturns409() throws Exception {
        String orderId = createOrder(100);
        String firstExecution = registerExecution(orderId, 20, "150.25");
        String secondExecution = registerExecution(orderId, 20, "150.75");
        String reversalId = uniqueId("rv");

        MvcResult first = reverse(orderId, reversalJson(reversalId, firstExecution));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(reversalId, secondExecution)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_ID_CONFLICT"));

        // 第二笔成交仍有效
        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(20);
        JsonNode page = getExecutions(orderId);
        assertThat(page.get("content").get(1).get("reversed").asBoolean()).isFalse();
    }

    @Test
    void sameReversalIdForDifferentExecutionOnAnotherOrderReturns409() throws Exception {
        String firstOrderId = createOrder(100);
        String secondOrderId = createOrder(100);
        String firstExecution = registerExecution(firstOrderId, 20, "150.25");
        String secondExecution = registerExecution(secondOrderId, 20, "150.75");
        String reversalId = uniqueId("rv");

        assertThat(reverse(firstOrderId, reversalJson(reversalId, firstExecution)).getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/executions/reversals", secondOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(reversalId, secondExecution)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_ID_CONFLICT"));

        assertThat(getOrder(secondOrderId).get("filledQuantity").asLong()).isEqualTo(20);
    }

    @Test
    void globalReversalEndpointsSupportCreateAndReplay() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String reversalId = uniqueId("rv");

        String globalBody = reversalJson(reversalId, executionId);
        mockMvc.perform(post("/api/executions/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(globalBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionId").value(executionId))
                .andExpect(jsonPath("$.orderStatus").value("OPEN"));

        mockMvc.perform(post("/api/executions/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(globalBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalId").value(reversalId));

        // 按成交标识定位的入口
        String otherOrderId = createOrder(50);
        String otherExecution = registerExecution(otherOrderId, 10, "150.25");
        mockMvc.perform(post("/api/executions/{executionId}/reversals", otherExecution)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reversalId\":\"" + uniqueId("rv") + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionId").value(otherExecution))
                .andExpect(jsonPath("$.orderStatus").value("OPEN"));
    }

    @Test
    void concurrentReversalsOfSameExecutionSucceedOnce() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                final String reversalId = uniqueId("rv");
                futures.add(pool.submit(() -> {
                    start.await();
                    return reverse(orderId, reversalJson(reversalId, executionId)).getResponse().getStatus();
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

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("reversed").asBoolean()).isTrue();
    }

    @Test
    void concurrentReplayWithSameReversalIdProducesSingleAuditRecord() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.25");
        String reversalId = uniqueId("rv");
        int concurrency = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reverse(orderId, reversalJson(reversalId, executionId)).getResponse().getStatus();
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

        assertThat(getOrder(orderId).get("filledQuantity").asLong()).isZero();
        JsonNode page = getExecutions(orderId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("reversed").asBoolean()).isTrue();
    }
}
