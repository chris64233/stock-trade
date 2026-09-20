package com.example.stocktrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderExecutionSummaryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private JsonNode querySummary(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void setExecutedAt(String orderId, String executionId, Instant instant) {
        jdbcTemplate.update("update execution_reports set executed_at = ? where order_id = ? and execution_id = ?",
                Timestamp.from(instant), orderId, executionId);
    }

    @Test
    void orderWithoutExecutionsReturnsZeroSummaryWithNullPriceAndTime() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.orderQuantity").value(100))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(100))
                .andExpect(jsonPath("$.executionCount").value(0))
                .andExpect(jsonPath("$.totalExecutedAmount").value(0.0000))
                .andExpect(jsonPath("$.averageExecutionPrice").value(nullValue()))
                .andExpect(jsonPath("$.lastExecutedAt").value(nullValue()));
    }

    @Test
    void singleExecutionIsSummarized() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.1234");
        Instant executedAt = Instant.parse("2026-01-01T08:00:00Z");
        setExecutedAt(orderId, executionId, executedAt);

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("orderId").asText()).isEqualTo(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(summary.get("orderQuantity").asLong()).isEqualTo(100);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(60);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        // 40 × 150.1234 = 6004.9360
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("6004.9360");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("150.1234");
        assertThat(Instant.parse(summary.get("lastExecutedAt").asText())).isEqualTo(executedAt);
    }

    @Test
    void multipleExecutionsUseWeightedAverageNotSimpleAverage() throws Exception {
        String orderId = createOrder(100);
        // 3 × 10.0000 + 1 × 10.0100 = 40.0100，加权均价 40.01 / 4 = 10.0025
        // 简单平均价为 (10.0000 + 10.0100) / 2 = 10.0050，可据此区分两种算法
        String first = registerExecution(orderId, 3, "10.0000");
        String second = registerExecution(orderId, 1, "10.0100");
        Instant earlier = Instant.parse("2026-01-01T08:00:00Z");
        Instant later = Instant.parse("2026-01-01T09:00:00Z");
        setExecutedAt(orderId, first, later);
        setExecutedAt(orderId, second, earlier);

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(2);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(4);
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("40.0100");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("10.0025");
        // lastExecutedAt 取全部成交回报中最晚的 executedAt
        assertThat(Instant.parse(summary.get("lastExecutedAt").asText())).isEqualTo(later);
    }

    @Test
    void averageExecutionPriceRoundsHalfUpToFourDecimals() throws Exception {
        String orderId = createOrder(100);
        // 1 × 10.0000 + 2 × 10.0001 = 30.0002，均价 30.0002 / 3 = 10.000066... -> 10.0001 (HALF_UP)
        registerExecution(orderId, 1, "10.0000");
        registerExecution(orderId, 2, "10.0001");

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("30.0002");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("10.0001");
    }

    @Test
    void fullyFilledOrderReportsFilledStatusAndZeroRemaining() throws Exception {
        String orderId = createOrder(50);
        registerExecution(orderId, 20, "150.0000");
        registerExecution(orderId, 30, "151.0000");

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("FILLED");
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(50);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(0);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(2);
        // 20 × 150 + 30 × 151 = 7530.0000，均价 7530 / 50 = 150.6000
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("7530.0000");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("150.6000");
    }

    @Test
    void partiallyFilledThenCancelledOrderKeepsExecutionSummary() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 30, "150.5000");

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk());

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(30);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(70);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("4515.0000");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("150.5000");
        assertThat(summary.get("lastExecutedAt").asText()).isNotBlank();
    }

    @Test
    void summaryOnlyIncludesExecutionsOfTheRequestedOrder() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        registerExecution(orderId, 10, "150.0000");
        registerExecution(otherOrderId, 5, "999.0000");
        registerExecution(otherOrderId, 7, "888.0000");

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(10);
        assertThat(summary.get("totalExecutedAmount").decimalValue()).isEqualByComparingTo("1500.0000");
        assertThat(summary.get("averageExecutionPrice").decimalValue()).isEqualByComparingTo("150.0000");

        JsonNode otherSummary = querySummary(otherOrderId);
        assertThat(otherSummary.get("executionCount").asLong()).isEqualTo(2);
        assertThat(otherSummary.get("filledQuantity").asLong()).isEqualTo(12);
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/orders/{id}/execution-summary", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/execution-summary"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
