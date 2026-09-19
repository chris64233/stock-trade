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
class FillApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueExecutionId() {
        return "ex-" + UUID.randomUUID();
    }

    private String createOrder(int quantity) throws Exception {
        String json = """
                {
                  "clientOrderId": "%s",
                  "accountId": "acc-1",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "quantity": %d,
                  "limitPrice": "150.25"
                }
                """.formatted("co-" + UUID.randomUUID(), quantity);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String fillJson(String executionId, Object quantity, Object price) {
        return """
                {
                  "executionId": %s,
                  "quantity": %s,
                  "price": %s
                }
                """.formatted(
                executionId == null ? "null" : "\"" + executionId + "\"",
                quantity, price);
    }

    private MvcResult registerFill(String orderId, String json) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    @Test
    void firstFillReturns201AndPartiallyFilled() throws Exception {
        String orderId = createOrder(100);
        String executionId = uniqueExecutionId();

        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 40, "150.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.executionId").value(executionId))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.quantity").value(40))
                .andExpect(jsonPath("$.price").value(150.25))
                .andExpect(jsonPath("$.executedAt").isNotEmpty())
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));
    }

    @Test
    void fillsAccumulateUntilFullyFilled() throws Exception {
        String orderId = createOrder(100);

        registerFill(orderId, fillJson(uniqueExecutionId(), 40, "150.25"));

        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(uniqueExecutionId(), 60, "150.30")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(100))
                .andExpect(jsonPath("$.remainingQuantity").value(0));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(100))
                .andExpect(jsonPath("$.remainingQuantity").value(0));
    }

    @Test
    void overFillReturns409AndPersistsNothing() throws Exception {
        String orderId = createOrder(100);
        registerFill(orderId, fillJson(uniqueExecutionId(), 60, "150.25"));

        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(uniqueExecutionId(), 41, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILL_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/fills"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(60))
                .andExpect(jsonPath("$.remainingQuantity").value(40));
    }

    @Test
    void duplicateExecutionIdReturnsOriginalFillWith200() throws Exception {
        String orderId = createOrder(100);
        String executionId = uniqueExecutionId();

        MvcResult first = registerFill(orderId, fillJson(executionId, 40, "150.25"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String fillId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String executedAt = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("executedAt").asText();

        // 等价的重复提交（含首尾空白与等价价格写法）返回原成交回报且不重复累计
        MvcResult second = registerFill(orderId, fillJson("  " + executionId + " ", 40, "150.2500"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(body.get("id").asText()).isEqualTo(fillId);
        assertThat(body.get("executedAt").asText()).isEqualTo(executedAt);
        assertThat(body.get("filledQuantity").asLong()).isEqualTo(40);

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));
    }

    @Test
    void conflictingExecutionIdReturns409() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String executionId = uniqueExecutionId();
        assertThat(registerFill(orderId, fillJson(executionId, 40, "150.25"))
                .getResponse().getStatus()).isEqualTo(201);

        // 相同 executionId 但数量不同
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 41, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));
        // 相同 executionId 但价格不同
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 40, "150.26")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));
        // 相同 executionId 但属于其他委托
        mockMvc.perform(post("/api/orders/{id}/fills", otherOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 40, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(40));
    }

    @Test
    void cancelPartiallyFilledOrderKeepsFilledQuantity() throws Exception {
        String orderId = createOrder(100);
        registerFill(orderId, fillJson(uniqueExecutionId(), 40, "150.25"));

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        // 重复撤单保持幂等
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40));
    }

    @Test
    void cancelledOrderRejectsNewFills() throws Exception {
        String orderId = createOrder(100);
        registerFill(orderId, fillJson(uniqueExecutionId(), 40, "150.25"));
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(uniqueExecutionId(), 10, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FILLABLE"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40));
    }

    @Test
    void filledOrderRejectsNewFillsAndCancel() throws Exception {
        String orderId = createOrder(100);
        registerFill(orderId, fillJson(uniqueExecutionId(), 100, "150.25"));

        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(uniqueExecutionId(), 1, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FILLABLE"));

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(100));
    }

    @Test
    void invalidFillRequestReturns400() throws Exception {
        String orderId = createOrder(100);
        String executionId = uniqueExecutionId();

        // executionId 为空、全空白、超长
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(null, 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson("   ", 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson("x".repeat(65), 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // quantity 越界或非整数
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 0, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 1000001, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 1.5, "1")))
                .andExpect(status().isBadRequest());
        // price 非正数或超过 4 位小数
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 1, "0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 1, "-5")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/fills", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(executionId, 1, "1.00001")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.filledQuantity").value(0));
    }

    @Test
    void fillOnUnknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/orders/{id}/fills", missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fillJson(uniqueExecutionId(), 1, "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/fills"));
    }
}
