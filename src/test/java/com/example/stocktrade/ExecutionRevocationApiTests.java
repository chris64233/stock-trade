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
class ExecutionRevocationApiTests {

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
        return registerExecution(orderId, uniqueId("ex"), quantity, price);
    }

    private String registerExecution(String orderId, String executionId, long quantity, String price)
            throws Exception {
        String json = """
                {
                  "executionId": "%s",
                  "quantity": %d,
                  "price": %s
                }
                """.formatted(executionId, quantity, price);
        MvcResult result = mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("executionId").asText();
    }

    private String revocationJson(String revokeId, String executionId) {
        return """
                {
                  "revokeId": %s,
                  "executionId": %s
                }
                """.formatted(
                revokeId == null ? "null" : "\"" + revokeId + "\"",
                executionId == null ? "null" : "\"" + executionId + "\"");
    }

    private MvcResult revoke(String json) throws Exception {
        return mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private JsonNode revokeExpect(String revokeId, String executionId, int expectedStatus)
            throws Exception {
        MvcResult result = revoke(revocationJson(revokeId, executionId));
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOrder(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void revokePartialFillReopensOrderAndWritesAudit() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.1234");
        String revokeId = uniqueId("rv");

        JsonNode body = revokeExpect(revokeId, executionId, 201);
        assertThat(body.get("revocationId").asText()).isNotBlank();
        assertThat(body.get("revokeId").asText()).isEqualTo(revokeId);
        assertThat(body.get("executionId").asText()).isEqualTo(executionId);
        assertThat(body.get("orderId").asText()).isEqualTo(orderId);
        assertThat(body.get("quantity").asLong()).isEqualTo(40);
        assertThat(body.get("price").asText()).isEqualTo("150.1234");
        assertThat(body.get("executedAt").asText()).isNotBlank();
        assertThat(body.get("revokedAt").asText()).isNotBlank();
        assertThat(body.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void revokeOneOfSeveralFillsKeepsPartiallyFilledStatus() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 30, "150.0000");
        String secondExecutionId = registerExecution(orderId, 20, "151.0000");

        JsonNode body = revokeExpect(uniqueId("rv"), secondExecutionId, 201);
        assertThat(body.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(70);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(70);
    }

    @Test
    void revokeFullFillReopensOrder() throws Exception {
        String orderId = createOrder(50);
        String executionId = registerExecution(orderId, 50, "150.2500");
        assertThat(getOrder(orderId).get("status").asText()).isEqualTo("FILLED");

        JsonNode body = revokeExpect(uniqueId("rv"), executionId, 201);
        assertThat(body.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(50);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(50);
    }

    @Test
    void reopenedOrderCanAcceptNewExecution() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        revokeExpect(uniqueId("rv"), executionId, 201);

        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId": "%s",
                                  "quantity": 60,
                                  "price": "150.5000"
                                }
                                """.formatted(uniqueId("ex"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(60))
                .andExpect(jsonPath("$.remainingQuantity").value(40));
    }

    @Test
    void alreadyRevokedExecutionReturns409AndKeepsState() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        JsonNode first = revokeExpect(uniqueId("rv"), executionId, 201);

        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(uniqueId("rv"), executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_REVOKED"))
                .andExpect(jsonPath("$.path").value("/api/executions/revocations"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(first.get("revocationId").asText()).isNotBlank();
    }

    @Test
    void unknownExecutionReturns404() throws Exception {
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(uniqueId("rv"), uniqueId("missing"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
    }

    @Test
    void revokeOnCancelledOrderKeepsCancelledStatus() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        JsonNode body = revokeExpect(uniqueId("rv"), executionId, 201);
        assertThat(body.get("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("filledQuantity").asLong()).isZero();
        assertThat(body.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("cancelledAt").asText()).isNotBlank();
    }

    @Test
    void idempotentReplayReturnsFirstResultEvenAfterOrderEvolves() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        String revokeId = "  " + uniqueId("rv") + "  ";

        JsonNode first = revokeExpect(revokeId.trim(), executionId, 201);

        registerExecution(orderId, 30, "151.0000");
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        MvcResult replayResult = revoke(revocationJson(revokeId, executionId));
        assertThat(replayResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode replay = objectMapper.readTree(replayResult.getResponse().getContentAsString());
        assertThat(replay.get("revocationId").asText())
                .isEqualTo(first.get("revocationId").asText());
        assertThat(replay.get("revokedAt").asText())
                .isEqualTo(first.get("revokedAt").asText());
        assertThat(replay.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(replay.get("filledQuantity").asLong()).isZero();
        assertThat(replay.get("remainingQuantity").asLong()).isEqualTo(100);

        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void sameRevokeIdForDifferentExecutionReturns409() throws Exception {
        String orderId = createOrder(100);
        String firstExecutionId = registerExecution(orderId, 30, "150.0000");
        String secondExecutionId = registerExecution(orderId, 20, "151.0000");
        String revokeId = uniqueId("rv");

        revokeExpect(revokeId, firstExecutionId, 201);

        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(revokeId, secondExecutionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVOKE_ID_CONFLICT"));

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(20);
    }

    @Test
    void revokedExecutionExcludedFromSummaryButVisibleInDetails() throws Exception {
        String orderId = createOrder(100);
        String keptExecutionId = registerExecution(orderId, 30, "150.0000");
        String revokedExecutionId = registerExecution(orderId, 20, "151.0000");
        revokeExpect(uniqueId("rv"), revokedExecutionId, 201);

        mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(30))
                .andExpect(jsonPath("$.remainingQuantity").value(70))
                .andExpect(jsonPath("$.executionCount").value(1))
                .andExpect(jsonPath("$.totalExecutedAmount").value(4500.0000))
                .andExpect(jsonPath("$.averageExecutionPrice").value(150.0000));

        MvcResult detailsResult = mockMvc.perform(get("/api/orders/{id}/executions", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        JsonNode items = objectMapper
                .readTree(detailsResult.getResponse().getContentAsString()).get("content");
        assertThat(items).hasSize(2);
        JsonNode revokedItem = null;
        JsonNode keptItem = null;
        for (JsonNode item : items) {
            if (item.get("executionId").asText().equals(revokedExecutionId)) {
                revokedItem = item;
            } else if (item.get("executionId").asText().equals(keptExecutionId)) {
                keptItem = item;
            }
        }
        assertThat(revokedItem).isNotNull();
        assertThat(revokedItem.get("revoked").asBoolean()).isTrue();
        assertThat(revokedItem.get("revokeId").asText()).isNotBlank();
        assertThat(revokedItem.get("revokedAt").asText()).isNotBlank();
        assertThat(keptItem).isNotNull();
        assertThat(keptItem.get("revoked").asBoolean()).isFalse();
        assertThat(keptItem.get("revokeId").isNull());
        assertThat(keptItem.get("revokedAt").isNull());
    }

    @Test
    void reRegisteringRevokedExecutionIdReturns409() throws Exception {
        String orderId = createOrder(100);
        String executionId = uniqueId("ex");
        registerExecution(orderId, executionId, 40, "150.2500");
        revokeExpect(uniqueId("rv"), executionId, 201);

        // 相同字段重新登记也必须冲突，不能恢复原成交
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId": "%s",
                                  "quantity": 40,
                                  "price": "150.2500"
                                }
                                """.formatted(executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));

        // 不同字段同样冲突
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId": "%s",
                                  "quantity": 41,
                                  "price": "150.2500"
                                }
                                """.formatted(executionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
    }

    @Test
    void invalidRevocationParametersReturn400() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 10, "150.0000");

        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson("   ", executionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(null, executionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(uniqueId("rv"), "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(uniqueId("rv"), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson("x".repeat(65), executionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 失败后委托与成交均未变化
        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isEqualTo(10);
    }

    @Test
    void failedRevocationLeavesExecutionOrderAndAuditUntouched() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 30, "150.0000");
        String revokedExecutionId = registerExecution(orderId, 20, "151.0000");
        revokeExpect(uniqueId("rv"), revokedExecutionId, 201);

        // 对已撤销成交再次撤销，成交、委托和审计都不能发生变化
        mockMvc.perform(post("/api/executions/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revocationJson(uniqueId("rv"), revokedExecutionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_REVOKED"));

        mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(30))
                .andExpect(jsonPath("$.remainingQuantity").value(70))
                .andExpect(jsonPath("$.executionCount").value(1));

        mockMvc.perform(get("/api/orders/{id}/executions", orderId))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void concurrentRevokesWithDifferentIdsSucceedOnce() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                final String revokeId = uniqueId("rv");
                futures.add(pool.submit(() -> {
                    start.await();
                    return revoke(revocationJson(revokeId, executionId)).getResponse().getStatus();
                }));
            }
            start.countDown();
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 409).count())
                    .isEqualTo(concurrency - 1);
        } finally {
            pool.shutdown();
        }

        JsonNode order = getOrder(orderId);
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);
    }

    @Test
    void concurrentReplayWithSameRevokeIdProducesSingleAuditRecord() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.2500");
        String revokeId = uniqueId("rv");
        int concurrency = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return revoke(revocationJson(revokeId, executionId)).getResponse().getStatus();
                }));
            }
            start.countDown();
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
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

        JsonNode order = getOrder(orderId);
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(100);
    }
}
