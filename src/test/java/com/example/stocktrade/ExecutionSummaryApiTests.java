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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionSummaryApiTests {

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
        return objectMapper.readTree(querySummaryRaw(orderId));
    }

    private String querySummaryRaw(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/{id}/execution-summary", orderId))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
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
                .andExpect(jsonPath("$.averageExecutionPrice").doesNotExist())
                .andExpect(jsonPath("$.lastExecutedAt").doesNotExist());

        String raw = querySummaryRaw(orderId);
        assertThat(raw).contains("\"totalExecutedAmount\":0.0000");
        JsonNode summary = objectMapper.readTree(raw);
        assertThat(summary.get("averageExecutionPrice").isNull()).isTrue();
        assertThat(summary.get("lastExecutedAt").isNull()).isTrue();
    }

    @Test
    void singleExecutionProducesMatchingSummary() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.1234");
        Instant executedAt = Instant.parse("2026-01-01T10:15:30Z");
        setExecutedAt(orderId, executionId, executedAt);

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("orderId").asText()).isEqualTo(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(summary.get("orderQuantity").asLong()).isEqualTo(100);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(60);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        // 40 × 150.1234 = 6004.9360
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("6004.9360"));
        assertThat(querySummaryRaw(orderId)).contains("\"totalExecutedAmount\":6004.9360");
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.1234"));
        assertThat(summary.get("lastExecutedAt").asText()).isEqualTo("2026-01-01T10:15:30Z");
    }

    @Test
    void multipleExecutionsUseWeightedAveragePriceAndSummedAmount() throws Exception {
        String orderId = createOrder(100);
        String first = registerExecution(orderId, 10, "100.00");
        String second = registerExecution(orderId, 30, "200.00");
        String third = registerExecution(orderId, 60, "50.00");
        setExecutedAt(orderId, first, Instant.parse("2026-01-01T09:00:00Z"));
        setExecutedAt(orderId, second, Instant.parse("2026-01-01T11:00:00Z"));
        setExecutedAt(orderId, third, Instant.parse("2026-01-01T10:00:00Z"));

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("FILLED");
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(100);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(0);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(3);
        // 10×100 + 30×200 + 60×50 = 10000.0000
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("10000.0000"));
        // 加权平均 10000 / 100 = 100.0000，而非简单平均 (100+200+50)/3 ≈ 116.6667
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(querySummaryRaw(orderId)).contains("\"averageExecutionPrice\":100.0000");
        // lastExecutedAt 取最晚的 executedAt
        assertThat(summary.get("lastExecutedAt").asText()).isEqualTo("2026-01-01T11:00:00Z");
    }

    @Test
    void weightedAverageRoundsHalfUpToFourDecimals() throws Exception {
        String orderId = createOrder(3);
        registerExecution(orderId, 1, "10.0000");
        registerExecution(orderId, 2, "10.0001");

        JsonNode summary = querySummary(orderId);
        // 总金额 1×10.0000 + 2×10.0001 = 30.0002
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("30.0002"));
        // 30.0002 / 3 = 10.0000666... → HALF_UP 保留 4 位为 10.0001
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("10.0001"));
        assertThat(querySummaryRaw(orderId)).contains("\"averageExecutionPrice\":10.0001");
    }

    @Test
    void fullyFilledOrderReportsFilledStatusAndZeroRemaining() throws Exception {
        String orderId = createOrder(50);
        registerExecution(orderId, 50, "150.25");

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("FILLED");
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(50);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(0);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("7512.5000"));
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.2500"));
    }

    @Test
    void partiallyFilledThenCancelledOrderKeepsExecutionSummary() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 30, "150.00");
        registerExecution(orderId, 20, "151.00");

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(50);
        assertThat(summary.get("remainingQuantity").asLong()).isEqualTo(50);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(2);
        // 30×150 + 20×151 = 7520.0000
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("7520.0000"));
        // 7520 / 50 = 150.4000
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.4000"));
        assertThat(summary.get("lastExecutedAt").asText()).isNotBlank();
    }

    @Test
    void summaryOnlyIncludesExecutionsOfTheRequestedOrder() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        registerExecution(orderId, 10, "100.00");
        registerExecution(otherOrderId, 5, "999.00");
        registerExecution(otherOrderId, 7, "888.00");

        JsonNode summary = querySummary(orderId);
        assertThat(summary.get("executionCount").asLong()).isEqualTo(1);
        assertThat(summary.get("filledQuantity").asLong()).isEqualTo(10);
        assertThat(summary.get("totalExecutedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(summary.get("averageExecutionPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100.0000"));

        JsonNode otherSummary = querySummary(otherOrderId);
        assertThat(otherSummary.get("executionCount").asLong()).isEqualTo(2);
        assertThat(otherSummary.get("filledQuantity").asLong()).isEqualTo(12);
    }

    @Test
    void summaryQueryDoesNotModifyOrderOrExecutions() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 40, "150.00");

        querySummary(orderId);
        querySummary(orderId);

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(40))
                .andExpect(jsonPath("$.remainingQuantity").value(60));
        mockMvc.perform(get("/api/orders/{id}/executions", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void unknownOrderReturns404WithUnifiedErrorBody() throws Exception {
        String missingId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/orders/{id}/execution-summary", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("委托不存在"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/execution-summary"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
