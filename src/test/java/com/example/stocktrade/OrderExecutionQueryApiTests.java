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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderExecutionQueryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private String registerExecution(String orderId, long quantity, String price) throws Exception {
        String json = """
                {
                  "executionId": "%s",
                  "quantity": %d,
                  "price": %s
                }
                """.formatted(uniqueExecutionId(), quantity, price);
        MvcResult result = mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("executionId").asText();
    }

    private JsonNode queryExecutions(String orderId, String queryString) throws Exception {
        String uri = "/api/orders/" + orderId + "/executions"
                + (queryString.isEmpty() ? "" : "?" + queryString);
        MvcResult result = mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> contentExecutionIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("executionId").asText()));
        return ids;
    }

    @Test
    void orderWithoutExecutionsReturns200WithEmptyContent() throws Exception {
        String orderId = createOrder(100);

        mockMvc.perform(get("/api/orders/{id}/executions", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.orderStatus").value("OPEN"))
                .andExpect(jsonPath("$.filledQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(100))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void partiallyFilledOrderReportsProgressAndExecutionItems() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.1234");

        JsonNode page = queryExecutions(orderId, "");
        assertThat(page.get("orderId").asText()).isEqualTo(orderId);
        assertThat(page.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(page.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(page.get("remainingQuantity").asLong()).isEqualTo(60);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);

        JsonNode item = page.get("content").get(0);
        assertThat(item.get("id").asText()).isNotBlank();
        assertThat(item.get("executionId").asText()).isEqualTo(executionId);
        assertThat(item.get("orderId").asText()).isEqualTo(orderId);
        assertThat(item.get("quantity").asLong()).isEqualTo(40);
        assertThat(item.get("price").decimalValue()).isEqualByComparingTo("150.1234");
        assertThat(item.get("executedAt").asText()).isNotBlank();
    }

    @Test
    void executionsAreSortedByExecutedAtThenIdAndPaginatedStably() throws Exception {
        String orderId = createOrder(100);
        List<String> executionIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            executionIds.add(registerExecution(orderId, 10, "150.25"));
        }

        // 通过数据库直接控制 executedAt，构造乱序时间与相同时间戳
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        Instant t2 = Instant.parse("2026-01-01T00:02:00Z");
        setExecutedAt(orderId, executionIds.get(2), t0);
        setExecutedAt(orderId, executionIds.get(0), t1);
        setExecutedAt(orderId, executionIds.get(4), t1);
        setExecutedAt(orderId, executionIds.get(1), t2);
        setExecutedAt(orderId, executionIds.get(3), t2);

        // executedAt 相同时按 id 升序
        List<String> t1Group = List.of(executionIds.get(0), executionIds.get(4)).stream()
                .sorted((a, b) -> dbId(orderId, a).compareTo(dbId(orderId, b)))
                .toList();
        List<String> t2Group = List.of(executionIds.get(1), executionIds.get(3)).stream()
                .sorted((a, b) -> dbId(orderId, a).compareTo(dbId(orderId, b)))
                .toList();
        List<String> expected = List.of(executionIds.get(2),
                t1Group.get(0), t1Group.get(1), t2Group.get(0), t2Group.get(1));

        JsonNode firstPage = queryExecutions(orderId, "size=2");
        assertThat(firstPage.get("page").asInt()).isEqualTo(0);
        assertThat(firstPage.get("size").asInt()).isEqualTo(2);
        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(5);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(3);
        assertThat(contentExecutionIds(firstPage)).containsExactly(expected.get(0), expected.get(1));

        JsonNode secondPage = queryExecutions(orderId, "page=1&size=2");
        assertThat(contentExecutionIds(secondPage)).containsExactly(expected.get(2), expected.get(3));

        JsonNode lastPage = queryExecutions(orderId, "page=2&size=2");
        assertThat(contentExecutionIds(lastPage)).containsExactly(expected.get(4));

        // 整页顺序与翻页拼接结果一致，保证顺序稳定
        JsonNode all = queryExecutions(orderId, "size=5");
        assertThat(contentExecutionIds(all)).containsExactlyElementsOf(expected);
    }

    @Test
    void executionsOfOtherOrdersAreNotReturned() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        String own = registerExecution(orderId, 30, "150.25");
        registerExecution(otherOrderId, 10, "150.25");
        registerExecution(otherOrderId, 20, "150.25");

        JsonNode page = queryExecutions(orderId, "");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(contentExecutionIds(page)).containsExactly(own);
        page.get("content").forEach(node ->
                assertThat(node.get("orderId").asText()).isEqualTo(orderId));

        JsonNode otherPage = queryExecutions(otherOrderId, "");
        assertThat(otherPage.get("totalElements").asLong()).isEqualTo(2);
        otherPage.get("content").forEach(node ->
                assertThat(node.get("orderId").asText()).isEqualTo(otherOrderId));
    }

    @Test
    void invalidPaginationParametersReturn400() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 10, "150.25");

        String[][] badParams = {
                {"page", "-1"}, {"page", "abc"}, {"page", ""}, {"page", "1.5"},
                {"size", "0"}, {"size", "101"}, {"size", "abc"}, {"size", ""}, {"size", "1.5"}
        };
        for (String[] param : badParams) {
            mockMvc.perform(get("/api/orders/{id}/executions", orderId)
                            .param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/executions"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        // 边界值合法
        mockMvc.perform(get("/api/orders/{id}/executions?page=0&size=1", orderId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/{id}/executions?page=0&size=100", orderId))
                .andExpect(status().isOk());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        String missingId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/orders/{id}/executions", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + missingId + "/executions"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private void setExecutedAt(String orderId, String executionId, Instant instant) {
        jdbcTemplate.update("update execution_reports set executed_at = ? where order_id = ? and execution_id = ?",
                Timestamp.from(instant), orderId, executionId);
    }

    private String dbId(String orderId, String executionId) {
        return jdbcTemplate.queryForObject(
                "select id from execution_reports where order_id = ? and execution_id = ?",
                String.class, orderId, executionId);
    }
}
