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
class OrderAmendmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.example.stocktrade.order.OrderAmendmentRepository amendmentRepository;

    private String uniqueAmendId() {
        return "am-" + UUID.randomUUID();
    }

    private String createOrder(int quantity) throws Exception {
        return createOrder(quantity, "150.2500");
    }

    private String createOrder(int quantity, String limitPrice) throws Exception {
        String json = """
                {
                  "clientOrderId": "co-%s",
                  "accountId": "acc-1",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "quantity": %d,
                  "limitPrice": "%s"
                }
                """.formatted(UUID.randomUUID(), quantity, limitPrice);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void fill(String orderId, String executionId, int quantity) throws Exception {
        String json = """
                {
                  "executionId": "%s",
                  "quantity": %d,
                  "price": "150.1000"
                }
                """.formatted(executionId, quantity);
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    private String amendmentJson(String amendId, Object quantity, Object limitPrice) {
        return """
                {
                  "amendId": %s,
                  "quantity": %s,
                  "limitPrice": %s
                }
                """.formatted(
                amendId == null ? "null" : "\"" + amendId + "\"",
                quantity,
                limitPrice);
    }

    private MvcResult amend(String orderId, String json) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private JsonNode amendExpectCreated(String orderId, String amendId, int quantity, String price)
            throws Exception {
        MvcResult result = amend(orderId, amendmentJson(amendId, quantity, "\"" + price + "\""));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void amendOpenOrderUpdatesQuantityPriceAndKeepsStatusOpen() throws Exception {
        String orderId = createOrder(100);
        String amendId = uniqueAmendId();

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson("  " + amendId + "  ", 120, "\"151.5000\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amendmentId").isNotEmpty())
                .andExpect(jsonPath("$.amendId").value(amendId))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.oldQuantity").value(100))
                .andExpect(jsonPath("$.newQuantity").value(120))
                .andExpect(jsonPath("$.oldLimitPrice").value(150.25))
                .andExpect(jsonPath("$.newLimitPrice").value(151.5))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(120))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.amendedAt").isNotEmpty());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(120))
                .andExpect(jsonPath("$.limitPrice").value(151.5))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(120));

        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
    }

    @Test
    void amendPartiallyFilledOrderPreservesFilledQuantityAndRecalculatesRemaining() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, "ex-" + UUID.randomUUID(), 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 150, "\"152.1234\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldQuantity").value(100))
                .andExpect(jsonPath("$.newQuantity").value(150))
                .andExpect(jsonPath("$.newLimitPrice").value(152.1234))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(110))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(150))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(110))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"));
    }

    @Test
    void newQuantityAtOrBelowFilledBoundaryIsRejected() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, "ex-" + UUID.randomUUID(), 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 40, "\"151.0000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 39, "\"151.0000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.limitPrice").value(150.25))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));
        assertThat(amendmentRepository.countByOrderId(orderId)).isZero();
    }

    @Test
    void newQuantityOneAboveFilledQuantitySucceeds() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, "ex-" + UUID.randomUUID(), 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 41, "\"151.0000\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingQuantity").value(1))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"));
    }

    @Test
    void cancelledAndFilledOrdersCannotBeAmended() throws Exception {
        String cancelledId = createOrder(100);
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/amendments", cancelledId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 120, "\"151.0000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_AMENDABLE"));

        String filledId = createOrder(50);
        fill(filledId, "ex-" + UUID.randomUUID(), 50);

        mockMvc.perform(post("/api/orders/{id}/amendments", filledId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 60, "\"151.0000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_AMENDABLE"));

        assertThat(amendmentRepository.countByOrderId(cancelledId)).isZero();
        assertThat(amendmentRepository.countByOrderId(filledId)).isZero();
    }

    @Test
    void invalidParametersReturnValidationError() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson("   ", 120, "\"151.0000\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 0, "\"151.0000\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), -1, "\"151.0000\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 120, "\"0\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 120, "\"151.12345\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendId(), 120.5, "\"151.0000\"")))
                .andExpect(status().isBadRequest());

        assertThat(amendmentRepository.countByOrderId(orderId)).isZero();
    }

    @Test
    void identicalReplayReturnsFirstResultWithoutNewMutation() throws Exception {
        String orderId = createOrder(100);
        String amendId = uniqueAmendId();

        JsonNode first = amendExpectCreated(orderId, amendId, 120, "151.5000");

        MvcResult replay = amend(orderId, amendmentJson(" " + amendId + " ", 120, "\"151.5000\""));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("amendmentId").asText()).isEqualTo(first.get("amendmentId").asText());
        assertThat(replayBody.get("amendedAt").asText()).isEqualTo(first.get("amendedAt").asText());
        assertThat(replayBody.get("oldQuantity").asInt()).isEqualTo(100);
        assertThat(replayBody.get("newQuantity").asInt()).isEqualTo(120);

        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
    }

    @Test
    void replayStillReturnsFirstSnapshotAfterOrderIsFilledOrCancelled() throws Exception {
        String filledId = createOrder(100);
        String amendForFilled = uniqueAmendId();
        JsonNode firstFilled = amendExpectCreated(filledId, amendForFilled, 120, "151.5000");
        fill(filledId, "ex-" + UUID.randomUUID(), 40);
        fill(filledId, "ex-" + UUID.randomUUID(), 80);

        MvcResult replayFilled = amend(filledId, amendmentJson(amendForFilled, 120, "\"151.5000\""));
        assertThat(replayFilled.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayFilledBody = objectMapper.readTree(replayFilled.getResponse().getContentAsString());
        assertThat(replayFilledBody.get("amendmentId").asText())
                .isEqualTo(firstFilled.get("amendmentId").asText());
        assertThat(replayFilledBody.get("status").asText()).isEqualTo("OPEN");
        assertThat(replayFilledBody.get("filledQuantity").asInt()).isZero();
        assertThat(replayFilledBody.get("remainingQuantity").asInt()).isEqualTo(120);

        String cancelledId = createOrder(100);
        String amendForCancelled = uniqueAmendId();
        JsonNode firstCancelled = amendExpectCreated(cancelledId, amendForCancelled, 90, "149.0000");
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledId)).andExpect(status().isOk());

        MvcResult replayCancelled = amend(cancelledId,
                amendmentJson(amendForCancelled, 90, "\"149.0000\""));
        assertThat(replayCancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayCancelledBody =
                objectMapper.readTree(replayCancelled.getResponse().getContentAsString());
        assertThat(replayCancelledBody.get("amendmentId").asText())
                .isEqualTo(firstCancelled.get("amendmentId").asText());
        assertThat(replayCancelledBody.get("newQuantity").asInt()).isEqualTo(90);

        assertThat(amendmentRepository.countByOrderId(filledId)).isEqualTo(1);
        assertThat(amendmentRepository.countByOrderId(cancelledId)).isEqualTo(1);
    }

    @Test
    void sameAmendIdWithDifferentFieldsReturnsConflict() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String amendId = uniqueAmendId();

        amendExpectCreated(orderId, amendId, 120, "151.5000");

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendId, 130, "\"151.5000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_ID_CONFLICT"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendId, 120, "\"152.0000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_ID_CONFLICT"));
        mockMvc.perform(post("/api/orders/{id}/amendments", otherOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendId, 120, "\"151.5000\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_ID_CONFLICT"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.quantity").value(120))
                .andExpect(jsonPath("$.limitPrice").value(151.5));
        mockMvc.perform(get("/api/orders/{id}", otherOrderId))
                .andExpect(jsonPath("$.quantity").value(100));
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
        assertThat(amendmentRepository.countByOrderId(otherOrderId)).isZero();
    }

    @Test
    void concurrentAmendmentsWithDifferentIdsDoNotLoseUpdates() throws Exception {
        String orderId = createOrder(100);
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult result = amend(orderId,
                            amendmentJson(uniqueAmendId(), 110 + index, "\"15" + (index + 1) + ".0000\""));
                    return result.getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdown();
        }

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.status").value("OPEN"));
        JsonNode order = objectMapper.readTree(
                mockMvc.perform(get("/api/orders/{id}", orderId)).andReturn().getResponse().getContentAsString());
        long finalQuantity = order.get("quantity").asLong();
        assertThat(finalQuantity).isBetween(110L, 110L + concurrency - 1);
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(finalQuantity);
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(concurrency);
    }

    @Test
    void concurrentReplayWithSameAmendIdProducesSingleAuditRecord() throws Exception {
        String orderId = createOrder(100);
        String amendId = uniqueAmendId();
        int concurrency = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult result = amend(orderId, amendmentJson(amendId, 120, "\"151.5000\""));
                    return result.getResponse().getStatus();
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

        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.quantity").value(120))
                .andExpect(jsonPath("$.limitPrice").value(151.5));
    }
}
