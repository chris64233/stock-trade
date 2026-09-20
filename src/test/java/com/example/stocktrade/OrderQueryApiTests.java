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
class OrderQueryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueAccountId() {
        return "acc-" + UUID.randomUUID();
    }

    private String createOrder(String accountId, String symbol, String side) throws Exception {
        String json = """
                {
                  "clientOrderId": "co-%s",
                  "accountId": "%s",
                  "symbol": "%s",
                  "side": "%s",
                  "quantity": 100,
                  "limitPrice": "150.25"
                }
                """.formatted(UUID.randomUUID(), accountId, symbol, side);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode query(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders" + queryString))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void defaultPaginationReturnsFirst20WithMetadata() throws Exception {
        String accountId = uniqueAccountId();
        for (int i = 0; i < 25; i++) {
            createOrder(accountId, "AAPL", "BUY");
        }

        JsonNode body = query("?accountId=" + accountId);
        assertThat(body.get("content").size()).isEqualTo(20);
        assertThat(body.get("page").asInt()).isEqualTo(0);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asLong()).isEqualTo(25);
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);

        JsonNode first = body.get("content").get(0);
        assertThat(first.get("id").asText()).isNotBlank();
        assertThat(first.get("clientOrderId").asText()).isNotBlank();
        assertThat(first.get("accountId").asText()).isEqualTo(accountId);
        assertThat(first.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(first.get("side").asText()).isEqualTo("BUY");
        assertThat(first.get("quantity").asLong()).isEqualTo(100);
        assertThat(first.get("limitPrice").asDouble()).isEqualTo(150.25);
        assertThat(first.get("status").asText()).isEqualTo("OPEN");
        assertThat(first.get("filledQuantity").asLong()).isEqualTo(0);
        assertThat(first.get("remainingQuantity").asLong()).isEqualTo(100);
        assertThat(first.get("createdAt").asText()).isNotBlank();
        assertThat(first.has("cancelledAt")).isTrue();

        JsonNode secondPage = query("?accountId=" + accountId + "&page=1");
        assertThat(secondPage.get("content").size()).isEqualTo(5);
        assertThat(secondPage.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void combinedFiltersMatchExactly() throws Exception {
        String accountId = uniqueAccountId();
        createOrder(accountId, "AAPL", "BUY");
        createOrder(accountId, "AAPL", "SELL");
        createOrder(accountId, "TSLA", "BUY");
        String cancelledId = createOrder(accountId, "AAPL", "BUY");
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledId))
                .andExpect(status().isOk());

        JsonNode body = query("?accountId=" + accountId + "&symbol=AAPL&status=OPEN&side=BUY");
        assertThat(body.get("totalElements").asLong()).isEqualTo(1);
        assertThat(body.get("totalPages").asInt()).isEqualTo(1);
        JsonNode order = body.get("content").get(0);
        assertThat(order.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(order.get("status").asText()).isEqualTo("OPEN");
        assertThat(order.get("side").asText()).isEqualTo("BUY");

        JsonNode cancelled = query("?accountId=" + accountId + "&status=CANCELLED");
        assertThat(cancelled.get("totalElements").asLong()).isEqualTo(1);
        assertThat(cancelled.get("content").get(0).get("id").asText()).isEqualTo(cancelledId);
    }

    @Test
    void filtersAreTrimmedAndCaseInsensitive() throws Exception {
        String accountId = uniqueAccountId();
        createOrder(accountId, "AAPL", "BUY");

        MvcResult result = mockMvc.perform(get("/api/orders")
                        .param("accountId", "  " + accountId + "  ")
                        .param("symbol", "  aapl  ")
                        .param("status", "open")
                        .param("side", "buy"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("totalElements").asLong()).isEqualTo(1);
        assertThat(body.get("content").get(0).get("symbol").asText()).isEqualTo("AAPL");
    }

    @Test
    void resultsAreStablySortedByCreatedAtDescThenIdDesc() throws Exception {
        String accountId = uniqueAccountId();
        for (int i = 0; i < 5; i++) {
            createOrder(accountId, "AAPL", "BUY");
        }

        JsonNode all = query("?accountId=" + accountId + "&size=10");
        List<JsonNode> expected = new ArrayList<>();
        all.get("content").forEach(expected::add);
        assertThat(expected).hasSize(5);

        for (int i = 0; i < expected.size() - 1; i++) {
            Instant current = Instant.parse(expected.get(i).get("createdAt").asText());
            Instant next = Instant.parse(expected.get(i + 1).get("createdAt").asText());
            assertThat(current).isAfterOrEqualTo(next);
            if (current.equals(next)) {
                assertThat(expected.get(i).get("id").asText())
                        .isGreaterThan(expected.get(i + 1).get("id").asText());
            }
        }

        List<String> pagedIds = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = query("?accountId=" + accountId + "&size=2&page=" + page);
            body.get("content").forEach(node -> pagedIds.add(node.get("id").asText()));
        }
        assertThat(pagedIds).containsExactlyElementsOf(
                expected.stream().map(node -> node.get("id").asText()).toList());
    }

    @Test
    void noMatchReturns200WithEmptyContent() throws Exception {
        JsonNode body = query("?accountId=" + uniqueAccountId());
        assertThat(body.get("content").size()).isEqualTo(0);
        assertThat(body.get("page").asInt()).isEqualTo(0);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asLong()).isEqualTo(0);
        assertThat(body.get("totalPages").asInt()).isEqualTo(0);
    }

    @Test
    void invalidParametersReturn400WithUniformError() throws Exception {
        String accountId = uniqueAccountId();
        String tooLongAccountId = "a".repeat(65);
        String tooLongSymbol = "S".repeat(11);

        List<String[]> badParams = List.of(
                new String[]{"accountId", "   "},
                new String[]{"accountId", tooLongAccountId},
                new String[]{"accountId", accountId, "symbol", "  "},
                new String[]{"accountId", accountId, "symbol", tooLongSymbol},
                new String[]{"accountId", accountId, "status", "UNKNOWN"},
                new String[]{"accountId", accountId, "side", "HOLD"},
                new String[]{"accountId", accountId, "page", "-1"},
                new String[]{"accountId", accountId, "size", "0"},
                new String[]{"accountId", accountId, "size", "101"},
                new String[]{"accountId", accountId, "page", "abc"},
                new String[]{"accountId", accountId, "size", "1.5"}
        );
        for (String[] params : badParams) {
            var request = get("/api/orders");
            for (int i = 0; i < params.length; i += 2) {
                request = request.param(params[i], params[i + 1]);
            }
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").value("/api/orders"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        // 缺少必填参数 accountId
        mockMvc.perform(get("/api/orders").param("symbol", "AAPL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
