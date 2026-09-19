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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueClientOrderId() {
        return "co-" + UUID.randomUUID();
    }

    private String orderJson(String clientOrderId, String accountId, String symbol,
                             String side, Object quantity, Object limitPrice) {
        return """
                {
                  "clientOrderId": %s,
                  "accountId": %s,
                  "symbol": %s,
                  "side": %s,
                  "quantity": %s,
                  "limitPrice": %s
                }
                """.formatted(
                clientOrderId == null ? "null" : "\"" + clientOrderId + "\"",
                accountId == null ? "null" : "\"" + accountId + "\"",
                symbol == null ? "null" : "\"" + symbol + "\"",
                side == null ? "null" : "\"" + side + "\"",
                quantity, limitPrice);
    }

    private String validOrderJson(String clientOrderId) {
        return orderJson(clientOrderId, "acc-1", "AAPL", "BUY", 100, "150.25");
    }

    private MvcResult createOrder(String json) throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    @Test
    void createOrderReturns201AndCanBeQueriedById() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOrderJson(uniqueClientOrderId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.accountId").value("acc-1"))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.limitPrice").value(150.25))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void symbolIsTrimmedAndUpperCased() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(uniqueClientOrderId(), "acc-1", "  aapl ", "SELL", 10, "1.2345")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.limitPrice").value(1.2345));
    }

    @Test
    void blankOrTooLongFieldsAreRejected() throws Exception {
        String id = uniqueClientOrderId();
        String tooLong = "x".repeat(65);
        String tooLongSymbol = "S".repeat(11);

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("   ", "acc-1", "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "  ", "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "   ", "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(tooLong, "acc-1", "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, tooLong, "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", tooLongSymbol, "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidQuantityPriceAndSideAreRejected() throws Exception {
        String id = uniqueClientOrderId();

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 0, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 1000001, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 1.5, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 1, "0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 1, "-5")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "BUY", 1, "1.00001")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(id, "acc-1", "AAPL", "HOLD", 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(null, "acc-1", "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void errorResponseHasUniformStructure() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(" ", "acc-1", "AAPL", "BUY", 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void duplicateSubmissionReturnsOriginalOrderWith200() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        String json = orderJson(clientOrderId, "acc-1", " aapl ", "BUY", 100, "150.25");

        MvcResult first = createOrder(json);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        // 规范化后字段完全相同的重复提交（含等价的价格写法）
        MvcResult second = createOrder(orderJson(clientOrderId, "acc-1", "AAPL", "BUY", 100, "150.2500"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("id").asText()).isEqualTo(firstId);
        assertThat(secondBody.get("symbol").asText()).isEqualTo("AAPL");
    }

    @Test
    void conflictingClientOrderIdReturns409() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        assertThat(createOrder(validOrderJson(clientOrderId)).getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(clientOrderId, "acc-2", "AAPL", "BUY", 100, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_ORDER_ID_CONFLICT"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void cancelTransitionsToCancelledAndIsIdempotent() throws Exception {
        MvcResult created = createOrder(validOrderJson(uniqueClientOrderId()));
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult cancelled = mockMvc.perform(post("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andReturn();
        String cancelledAt = objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("cancelledAt").asText();

        // 重复撤单返回原结果且不修改第一次的撤单时间
        mockMvc.perform(post("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value(cancelledAt));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value(cancelledAt));
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/orders/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId));

        mockMvc.perform(post("/api/orders/{id}/cancel", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}
