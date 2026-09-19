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

    private String validRequestJson(String clientOrderId) {
        return """
                {
                  "clientOrderId": "%s",
                  "accountId": "acc-1",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "quantity": 100,
                  "limitPrice": 123.4567
                }
                """.formatted(clientOrderId);
    }

    private JsonNode createOrder(String clientOrderId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(clientOrderId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createAndGetById() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        JsonNode created = createOrder(clientOrderId);

        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(created.get("clientOrderId").asText()).isEqualTo(clientOrderId);
        assertThat(created.get("status").asText()).isEqualTo("OPEN");
        assertThat(created.get("createdAt").asText()).isNotBlank();
        assertThat(created.get("cancelledAt").isNull()).isTrue();

        mockMvc.perform(get("/api/orders/{id}", created.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id").asText()))
                .andExpect(jsonPath("$.clientOrderId").value(clientOrderId))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.limitPrice").value(123.4567))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void getByIdNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", "non-existent-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/orders/non-existent-id"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void symbolIsTrimmedAndUppercased() throws Exception {
        String body = """
                {
                  "clientOrderId": "  %s  ",
                  "accountId": "  acc-1  ",
                  "symbol": "  aapl ",
                  "side": "SELL",
                  "quantity": 1,
                  "limitPrice": 0.0001
                }
                """.formatted(uniqueClientOrderId());

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(created.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(created.get("accountId").asText()).isEqualTo("acc-1");
        assertThat(created.get("clientOrderId").asText()).doesNotContain(" ");
    }

    @Test
    void validationErrorsReturn400() throws Exception {
        String[] invalidBodies = {
                // clientOrderId 空白
                """
                {"clientOrderId":"   ","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":1}
                """,
                // clientOrderId 超长
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":1}
                """.formatted("x".repeat(65)),
                // accountId 空白
                """
                {"clientOrderId":"%s","accountId":" ","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":1}
                """.formatted(uniqueClientOrderId()),
                // symbol 超长
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"TOOLONGSYMBOL","side":"BUY","quantity":1,"limitPrice":1}
                """.formatted(uniqueClientOrderId()),
                // side 非法
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"HOLD","quantity":1,"limitPrice":1}
                """.formatted(uniqueClientOrderId()),
                // quantity 为 0
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":0,"limitPrice":1}
                """.formatted(uniqueClientOrderId()),
                // quantity 超上限
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1000001,"limitPrice":1}
                """.formatted(uniqueClientOrderId()),
                // limitPrice 为 0
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":0}
                """.formatted(uniqueClientOrderId()),
                // limitPrice 为负
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":-1}
                """.formatted(uniqueClientOrderId()),
                // limitPrice 超过 4 位小数
                """
                {"clientOrderId":"%s","accountId":"acc-1","symbol":"AAPL","side":"BUY","quantity":1,"limitPrice":1.00001}
                """.formatted(uniqueClientOrderId())
        };

        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").value("/api/orders"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
    }

    @Test
    void duplicateSubmissionReturnsOriginalWith200() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        JsonNode first = createOrder(clientOrderId);

        MvcResult second = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(clientOrderId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode duplicated = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(duplicated.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(duplicated.get("createdAt").asText()).isEqualTo(first.get("createdAt").asText());
    }

    @Test
    void duplicateSubmissionWithNormalizedFieldsReturnsOriginal() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        JsonNode first = createOrder(clientOrderId);

        String normalizedVariant = """
                {
                  "clientOrderId": " %s ",
                  "accountId": "acc-1",
                  "symbol": "aapl",
                  "side": "BUY",
                  "quantity": 100,
                  "limitPrice": 123.4567
                }
                """.formatted(clientOrderId);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(normalizedVariant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));
    }

    @Test
    void conflictingClientOrderIdReturns409() throws Exception {
        String clientOrderId = uniqueClientOrderId();
        createOrder(clientOrderId);

        String conflicting = """
                {
                  "clientOrderId": "%s",
                  "accountId": "acc-1",
                  "symbol": "AAPL",
                  "side": "SELL",
                  "quantity": 100,
                  "limitPrice": 123.4567
                }
                """.formatted(clientOrderId);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_ORDER_ID_CONFLICT"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void cancelOpenOrder() throws Exception {
        JsonNode created = createOrder(uniqueClientOrderId());
        String id = created.get("id").asText();

        mockMvc.perform(post("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelAlreadyCancelledKeepsOriginalCancelledAt() throws Exception {
        JsonNode created = createOrder(uniqueClientOrderId());
        String id = created.get("id").asText();

        MvcResult first = mockMvc.perform(post("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andReturn();
        String firstCancelledAt = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("cancelledAt").asText();

        MvcResult second = mockMvc.perform(post("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn();
        String secondCancelledAt = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("cancelledAt").asText();

        assertThat(secondCancelledAt).isEqualTo(firstCancelledAt);
    }

    @Test
    void cancelNotFoundReturns404() throws Exception {
        mockMvc.perform(post("/api/orders/{id}/cancel", "non-existent-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/non-existent-id/cancel"));
    }
}
