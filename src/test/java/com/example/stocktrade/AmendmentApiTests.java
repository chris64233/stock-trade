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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AmendmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.example.stocktrade.order.OrderAmendmentRepository amendmentRepository;

    private String uniqueAmendmentId() {
        return "am-" + UUID.randomUUID();
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

    private void fill(String orderId, int quantity) throws Exception {
        String json = """
                {
                  "executionId": "ex-%s",
                  "quantity": %d,
                  "price": "150.25"
                }
                """.formatted(UUID.randomUUID(), quantity);
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    private String amendmentJson(String amendmentId, Object quantity, Object limitPrice) {
        return """
                {
                  "amendmentId": %s,
                  "quantity": %s,
                  "limitPrice": %s
                }
                """.formatted(
                amendmentId == null ? "null" : "\"" + amendmentId + "\"",
                quantity, limitPrice);
    }

    private MvcResult amend(String orderId, String json) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    @Test
    void unfilledOrderCanBeAmended() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson("  " + uniqueAmendmentId() + "  ", 120, "151.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amendmentId").isNotEmpty())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.oldQuantity").value(100))
                .andExpect(jsonPath("$.newQuantity").value(120))
                .andExpect(jsonPath("$.oldLimitPrice").value(150.25))
                .andExpect(jsonPath("$.newLimitPrice").value(151.5))
                .andExpect(jsonPath("$.orderStatus").value("OPEN"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(120))
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
    void partiallyFilledOrderCanBeAmendedAndKeepsFilledQuantity() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "152")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldQuantity").value(100))
                .andExpect(jsonPath("$.newQuantity").value(120))
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(80));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(120))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(80))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"));
    }

    @Test
    void quantityMustBeStrictlyGreaterThanFilledQuantity() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 39, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 40, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 41, "150.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newQuantity").value(41))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(1));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(41))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(1));
    }

    @Test
    void filledOrCancelledOrderCannotBeAmended() throws Exception {
        String filledId = createOrder(50);
        fill(filledId, 50);
        mockMvc.perform(post("/api/orders/{id}/amendments", filledId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 60, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_AMENDABLE"));

        String cancelledId = createOrder(100);
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{id}/amendments", cancelledId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_AMENDABLE"));

        assertThat(amendmentRepository.countByOrderId(filledId) + amendmentRepository.countByOrderId(cancelledId)).isZero();
    }

    @Test
    void identicalAmendmentIsReplayedWithoutNewAudit() throws Exception {
        String orderId = createOrder(100);
        String amendmentId = uniqueAmendmentId();

        MvcResult first = amend(orderId, amendmentJson(amendmentId, 120, "150.2500"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult second = amend(orderId, amendmentJson(amendmentId, 120, "150.25"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(secondBody.get("amendedAt").asText()).isEqualTo(firstBody.get("amendedAt").asText());
        assertThat(secondBody.get("oldQuantity").asLong()).isEqualTo(100);
        assertThat(secondBody.get("newQuantity").asLong()).isEqualTo(120);

        // 之后委托再次被改单，幂等重放仍返回首次改单结果
        amend(orderId, amendmentJson(uniqueAmendmentId(), 130, "153"));
        MvcResult replay = amend(orderId, amendmentJson(amendmentId, 120, "150.25"));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("oldQuantity").asLong()).isEqualTo(100);
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(replayBody.get("filledQuantity").asLong()).isZero();
        assertThat(replayBody.get("remainingQuantity").asLong()).isEqualTo(120);

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(130));

        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(2);
    }

    @Test
    void replayStillReturnsFirstResultAfterOrderFilledOrCancelled() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, 40);
        String amendmentId = uniqueAmendmentId();

        MvcResult first = amend(orderId, amendmentJson(amendmentId, 120, "150.25"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        // 改单后委托被完全成交
        fill(orderId, 80);
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("FILLED"));

        MvcResult replayAfterFill = amend(orderId, amendmentJson(amendmentId, 120, "150.25"));
        assertThat(replayAfterFill.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replayAfterFill.getResponse().getContentAsString());
        assertThat(replayBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(replayBody.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(replayBody.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(replayBody.get("remainingQuantity").asLong()).isEqualTo(80);
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);

        // 另一个委托：改单后撤单，重放仍返回首次改单结果
        String cancelledOrderId = createOrder(100);
        String cancelledAmendmentId = uniqueAmendmentId();
        assertThat(amend(cancelledOrderId, amendmentJson(cancelledAmendmentId, 120, "150.25"))
                .getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledOrderId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{id}/amendments", cancelledOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(cancelledAmendmentId, 120, "150.25")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("OPEN"))
                .andExpect(jsonPath("$.remainingQuantity").value(120));
    }

    @Test
    void conflictingAmendmentIdReturns409() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String amendmentId = uniqueAmendmentId();
        assertThat(amend(orderId, amendmentJson(amendmentId, 120, "150.25"))
                .getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendmentId, 121, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMENDMENT_ID_CONFLICT"));

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendmentId, 120, "150.26")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMENDMENT_ID_CONFLICT"));

        mockMvc.perform(post("/api/orders/{id}/amendments", otherOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendmentId, 120, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMENDMENT_ID_CONFLICT"));

        // 冲突不会改动委托，也不会新增审计
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(120));
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
    }

    @Test
    void failedAmendmentRollsBackOrderAndAudit() throws Exception {
        String orderId = createOrder(100);
        fill(orderId, 40);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 40, "151")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));

        // 委托与审计均未发生变化
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.limitPrice").value(150.25))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));
        assertThat(amendmentRepository.countByOrderId(orderId)).isZero();

        // 失败使用过的 amendmentId 未占用，合法请求仍可成功
        String amendmentId = uniqueAmendmentId();
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendmentId, 30, "151")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMEND_QUANTITY_INVALID"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(amendmentId, 80, "151")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newQuantity").value(80))
                .andExpect(jsonPath("$.remainingQuantity").value(40));
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
    }

    @Test
    void invalidAmendmentParametersReturn400() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson("   ", 120, "150.25")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(null, 120, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson("x".repeat(65), 120, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 0, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), -5, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 1000001, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 1.5, "150.25")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "-5")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/amendments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "150.12345")))
                .andExpect(status().isBadRequest());

        assertThat(amendmentRepository.countByOrderId(orderId)).isZero();
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/orders/{id}/amendments", missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentJson(uniqueAmendmentId(), 120, "150.25")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/amendments"));
    }

    @Test
    void concurrentAmendmentsDoNotLoseUpdates() throws Exception {
        String orderId = createOrder(100);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            String jsonA = amendmentJson(uniqueAmendmentId(), 120, "150.25");
            String jsonB = amendmentJson(uniqueAmendmentId(), 130, "151");
            Future<MvcResult> futureA = pool.submit(() -> {
                ready.countDown();
                start.await();
                return amend(orderId, jsonA);
            });
            Future<MvcResult> futureB = pool.submit(() -> {
                ready.countDown();
                start.await();
                return amend(orderId, jsonB);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int statusA = futureA.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int statusB = futureB.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(statusA).isEqualTo(201);
            assertThat(statusB).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        JsonNode order = objectMapper.readTree(mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        long finalQuantity = order.get("quantity").asLong();
        assertThat(finalQuantity).isIn(120L, 130L);
        assertThat(order.get("filledQuantity").asLong()).isZero();
        assertThat(order.get("remainingQuantity").asLong()).isEqualTo(finalQuantity);
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(2);
    }

    @Test
    void concurrentReplayCreatesOnlyOneAudit() throws Exception {
        String orderId = createOrder(100);
        String amendmentId = uniqueAmendmentId();
        String json = amendmentJson(amendmentId, 120, "150.25");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcResult> futureA = pool.submit(() -> {
                ready.countDown();
                start.await();
                return amend(orderId, json);
            });
            Future<MvcResult> futureB = pool.submit(() -> {
                ready.countDown();
                start.await();
                return amend(orderId, json);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int statusA = futureA.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int statusB = futureB.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(statusA + statusB).isEqualTo(201 + 200);
        } finally {
            pool.shutdownNow();
        }

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(120));
        assertThat(amendmentRepository.countByOrderId(orderId)).isEqualTo(1);
    }
}
