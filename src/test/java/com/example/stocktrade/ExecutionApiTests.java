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
class ExecutionApiTests {

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

    private String executionJson(String executionId, Object quantity, Object price) {
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

    private MvcResult registerExecution(String orderId, String json) throws Exception {
        return mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    @Test
    void firstExecutionReturns201WithOrderProgress() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson("  " + uniqueExecutionId() + "  ", 40, "150.1234")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.quantity").value(40))
                .andExpect(jsonPath("$.price").value(150.1234))
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
    void cumulativeFillsTransitionToFilled() throws Exception {
        String orderId = createOrder(100);

        registerExecution(orderId, executionJson(uniqueExecutionId(), 40, "150.25"));
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 60, "150.5")))
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
        registerExecution(orderId, executionJson(uniqueExecutionId(), 40, "150.25"));

        String executionId = uniqueExecutionId();
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(executionId, 61, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILL_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/executions"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        // 委托未被修改
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40));

        // 成交回报未保存，相同 executionId 以合法数量重新提交可成功
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(executionId, 60, "150.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("FILLED"));
    }

    @Test
    void duplicateExecutionIdReturnsOriginalWith200() throws Exception {
        String orderId = createOrder(100);
        String executionId = uniqueExecutionId();

        MvcResult first = registerExecution(orderId, executionJson(executionId, 40, "150.25"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        // 等价的价格写法视为同一成交回报
        MvcResult second = registerExecution(orderId, executionJson(executionId, 40, "150.2500"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        assertThat(secondBody.get("executedAt").asText()).isEqualTo(firstBody.get("executedAt").asText());
        assertThat(secondBody.get("filledQuantity").asLong()).isEqualTo(40);

        // 已成交数量不得重复累加
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(40));
    }

    @Test
    void conflictingExecutionIdReturns409() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String executionId = uniqueExecutionId();
        assertThat(registerExecution(orderId, executionJson(executionId, 40, "150.25"))
                .getResponse().getStatus()).isEqualTo(201);

        // 同一委托但数量不同
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(executionId, 41, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));

        // 同一委托但价格不同
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(executionId, 40, "150.26")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));

        // 不同委托复用相同 executionId
        mockMvc.perform(post("/api/orders/{id}/executions", otherOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(executionId, 40, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_ID_CONFLICT"));
    }

    @Test
    void invalidExecutionParametersReturn400() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson("   ", 1, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(null, 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson("x".repeat(65), 1, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 0, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1000001, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1.5, "1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1, "0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1, "-5")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1, "1.00001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/orders/{id}/executions", missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1, "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/executions"));
    }

    @Test
    void partiallyFilledOrderCanBeCancelledAndKeepsFilledQuantity() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, executionJson(uniqueExecutionId(), 40, "150.25"));

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

        // 已取消委托不能接收新的成交回报
        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 10, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FILLABLE"));
    }

    @Test
    void filledOrderRejectsCancelAndNewExecutions() throws Exception {
        String orderId = createOrder(50);
        registerExecution(orderId, executionJson(uniqueExecutionId(), 50, "150.25"));

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));

        mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executionJson(uniqueExecutionId(), 1, "150.25")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FILLABLE"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(50));
    }
}
