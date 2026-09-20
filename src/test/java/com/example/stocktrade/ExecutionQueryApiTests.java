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
class ExecutionQueryApiTests {

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
                  "price": "%s"
                }
                """.formatted(UUID.randomUUID(), quantity, price);
        MvcResult result = mockMvc.perform(post("/api/orders/{id}/executions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode queryExecutions(String orderId, String queryString) throws Exception {
        String uri = "/api/orders/" + orderId + "/executions" + (queryString.isEmpty() ? "" : "?" + queryString);
        MvcResult result = mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> contentIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    @Test
    void orderWithoutExecutionsReturnsEmptyPage() throws Exception {
        String orderId = createOrder(100);

        JsonNode page = queryExecutions(orderId, "");
        assertThat(page.get("orderId").asText()).isEqualTo(orderId);
        assertThat(page.get("orderStatus").asText()).isEqualTo("OPEN");
        assertThat(page.get("filledQuantity").asLong()).isEqualTo(0);
        assertThat(page.get("remainingQuantity").asLong()).isEqualTo(100);
        assertThat(page.get("content").size()).isEqualTo(0);
        assertThat(page.get("page").asInt()).isEqualTo(0);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalElements").asLong()).isEqualTo(0);
        assertThat(page.get("totalPages").asInt()).isEqualTo(0);
    }

    @Test
    void partiallyFilledOrderReturnsExecutionWithProgress() throws Exception {
        String orderId = createOrder(100);
        String executionId = registerExecution(orderId, 40, "150.1234");

        JsonNode page = queryExecutions(orderId, "");
        assertThat(page.get("orderStatus").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(page.get("filledQuantity").asLong()).isEqualTo(40);
        assertThat(page.get("remainingQuantity").asLong()).isEqualTo(60);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);

        JsonNode item = page.get("content").get(0);
        assertThat(item.get("id").asText()).isEqualTo(executionId);
        assertThat(item.get("executionId").asText()).isNotBlank();
        assertThat(item.get("orderId").asText()).isEqualTo(orderId);
        assertThat(item.get("quantity").asLong()).isEqualTo(40);
        assertThat(item.get("price").decimalValue()).isEqualByComparingTo("150.1234");
        assertThat(item.get("executedAt").asText()).isNotBlank();
    }

    @Test
    void multipleExecutionsAreSortedAndPaginatedStably() throws Exception {
        String orderId = createOrder(100);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(registerExecution(orderId, 10, "150.25"));
        }
        // 构造 executedAt 相同的记录，验证按 id 升序的次序
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("update execution_reports set executed_at = ? where id = ?",
                Timestamp.from(same), created.get(1));
        jdbcTemplate.update("update execution_reports set executed_at = ? where id = ?",
                Timestamp.from(same), created.get(2));
        List<String> tieOrder = List.of(created.get(1), created.get(2)).stream().sorted().toList();

        JsonNode page0 = queryExecutions(orderId, "page=0&size=2");
        assertThat(page0.get("page").asInt()).isEqualTo(0);
        assertThat(page0.get("size").asInt()).isEqualTo(2);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(contentIds(page0)).containsExactly(tieOrder.get(0), tieOrder.get(1));

        JsonNode page1 = queryExecutions(orderId, "page=1&size=2");
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(contentIds(page1)).containsExactly(created.get(0), created.get(3));

        JsonNode page2 = queryExecutions(orderId, "page=2&size=2");
        assertThat(contentIds(page2)).containsExactly(created.get(4));

        // 合并各页后整体按 executedAt 升序（相同则 id 升序）且无重复
        List<String> combined = new ArrayList<>();
        combined.addAll(contentIds(page0));
        combined.addAll(contentIds(page1));
        combined.addAll(contentIds(page2));
        assertThat(combined).containsExactlyInAnyOrderElementsOf(created);
        JsonNode all = queryExecutions(orderId, "size=100");
        List<Instant> executedAts = new ArrayList<>();
        all.get("content").forEach(node -> executedAts.add(Instant.parse(node.get("executedAt").asText())));
        assertThat(executedAts).isSorted();
    }

    @Test
    void executionsOfOtherOrdersAreNotReturned() throws Exception {
        String orderId = createOrder(100);
        String otherOrderId = createOrder(100);
        registerExecution(orderId, 10, "150.25");
        registerExecution(otherOrderId, 20, "150.25");
        registerExecution(otherOrderId, 30, "150.25");

        JsonNode page = queryExecutions(orderId, "size=100");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").size()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("orderId").asText()).isEqualTo(orderId);
        assertThat(page.get("content").get(0).get("quantity").asLong()).isEqualTo(10);
    }

    @Test
    void invalidPaginationParametersReturn400() throws Exception {
        String orderId = createOrder(100);
        String[][] invalidParams = {
                {"page", "-1"},
                {"page", "abc"},
                {"page", "1.5"},
                {"page", "   "},
                {"size", "0"},
                {"size", "101"},
                {"size", "-5"},
                {"size", "abc"},
                {"size", "  "}
        };
        for (String[] param : invalidParams) {
            mockMvc.perform(get("/api/orders/{id}/executions", orderId)
                            .param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/executions"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
    }

    @Test
    void boundaryPaginationParametersAreAccepted() throws Exception {
        String orderId = createOrder(100);
        registerExecution(orderId, 10, "150.25");

        JsonNode page = queryExecutions(orderId, "page=0&size=1");
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);

        JsonNode maxSize = queryExecutions(orderId, "size=100");
        assertThat(maxSize.get("size").asInt()).isEqualTo(100);
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
}
