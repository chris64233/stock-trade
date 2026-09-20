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
class OrderQueryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uniqueAccountId() {
        return "acc-" + UUID.randomUUID();
    }

    private String createOrder(String accountId, String symbol, String side) throws Exception {
        String json = """
                {
                  "clientOrderId": "%s",
                  "accountId": "%s",
                  "symbol": "%s",
                  "side": "%s",
                  "quantity": 100,
                  "limitPrice": "150.25"
                }
                """.formatted("co-" + UUID.randomUUID(), accountId, symbol, side);
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode query(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders?" + queryString))
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
    void defaultPaginationReturnsFirstPageOf20() throws Exception {
        String accountId = uniqueAccountId();
        for (int i = 0; i < 25; i++) {
            createOrder(accountId, "AAPL", "BUY");
        }

        JsonNode firstPage = query("accountId=" + accountId);
        assertThat(firstPage.get("page").asInt()).isEqualTo(0);
        assertThat(firstPage.get("size").asInt()).isEqualTo(20);
        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(25);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("content").size()).isEqualTo(20);

        JsonNode secondPage = query("accountId=" + accountId + "&page=1&size=20");
        assertThat(secondPage.get("page").asInt()).isEqualTo(1);
        assertThat(secondPage.get("content").size()).isEqualTo(5);

        List<String> firstIds = contentIds(firstPage);
        List<String> secondIds = contentIds(secondPage);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    void combinedFiltersMatchExactly() throws Exception {
        String accountId = uniqueAccountId();
        String otherAccount = uniqueAccountId();
        String target = createOrder(accountId, "AAPL", "BUY");
        String cancelled = createOrder(accountId, "AAPL", "BUY");
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelled))
                .andExpect(status().isOk());
        createOrder(accountId, "AAPL", "SELL");
        createOrder(accountId, "TSLA", "BUY");
        createOrder(otherAccount, "AAPL", "BUY");

        JsonNode page = query("accountId=" + accountId + "&symbol=AAPL&side=BUY&status=OPEN");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(contentIds(page)).containsExactly(target);

        JsonNode cancelledPage = query("accountId=" + accountId + "&status=CANCELLED");
        assertThat(contentIds(cancelledPage)).containsExactly(cancelled);

        // content 中的字段与单笔查询保持一致
        MvcResult single = mockMvc.perform(get("/api/orders/{id}", target))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode singleBody = objectMapper.readTree(single.getResponse().getContentAsString());
        assertThat(page.get("content").get(0)).isEqualTo(singleBody);
    }

    @Test
    void filtersAreTrimmedAndCaseInsensitive() throws Exception {
        String accountId = uniqueAccountId();
        String id = createOrder(accountId, "AAPL", "BUY");

        MvcResult result = mockMvc.perform(get("/api/orders")
                        .param("accountId", "  " + accountId + "  ")
                        .param("symbol", "  aapl  ")
                        .param("status", "open")
                        .param("side", "buy"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(contentIds(page)).containsExactly(id);
        assertThat(page.get("content").get(0).get("symbol").asText()).isEqualTo("AAPL");
    }

    @Test
    void orderingIsStableWithCreatedAtDescThenIdDesc() throws Exception {
        String accountId = uniqueAccountId();
        String first = createOrder(accountId, "AAPL", "BUY");
        String second = createOrder(accountId, "AAPL", "BUY");
        String third = createOrder(accountId, "AAPL", "BUY");

        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-01-02T00:00:00Z");
        jdbcTemplate.update("update stock_orders set created_at = ? where id = ?",
                Timestamp.from(same), first);
        jdbcTemplate.update("update stock_orders set created_at = ? where id = ?",
                Timestamp.from(same), second);
        jdbcTemplate.update("update stock_orders set created_at = ? where id = ?",
                Timestamp.from(later), third);

        JsonNode page = query("accountId=" + accountId);
        List<String> expectedTieOrder = List.of(first, second).stream()
                .sorted((a, b) -> b.compareTo(a))
                .toList();
        assertThat(contentIds(page)).containsExactly(third, expectedTieOrder.get(0), expectedTieOrder.get(1));

        // 翻页时顺序保持稳定
        JsonNode page1 = query("accountId=" + accountId + "&size=2&page=0");
        JsonNode page2 = query("accountId=" + accountId + "&size=2&page=1");
        List<String> combined = new ArrayList<>(contentIds(page1));
        combined.addAll(contentIds(page2));
        assertThat(combined).containsExactly(third, expectedTieOrder.get(0), expectedTieOrder.get(1));
    }

    @Test
    void noMatchReturns200WithEmptyContent() throws Exception {
        JsonNode page = query("accountId=" + uniqueAccountId());
        assertThat(page.get("content").size()).isEqualTo(0);
        assertThat(page.get("totalElements").asLong()).isEqualTo(0);
        assertThat(page.get("totalPages").asInt()).isEqualTo(0);
        assertThat(page.get("page").asInt()).isEqualTo(0);
        assertThat(page.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void invalidParametersReturn400WithUniformError() throws Exception {
        String accountId = uniqueAccountId();
        String tooLongAccount = "a".repeat(65);
        String tooLongSymbol = "S".repeat(11);

        String[][] badParams = {
                {"accountId", "   "},
                {"accountId", tooLongAccount},
                {"accountId", accountId, "symbol", "   "},
                {"accountId", accountId, "symbol", tooLongSymbol},
                {"accountId", accountId, "status", "UNKNOWN"},
                {"accountId", accountId, "status", ""},
                {"accountId", accountId, "side", "HOLD"},
                {"accountId", accountId, "page", "-1"},
                {"accountId", accountId, "page", "abc"},
                {"accountId", accountId, "size", "0"},
                {"accountId", accountId, "size", "101"},
                {"accountId", accountId, "size", "1.5"}
        };
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

        // 缺少必填的 accountId
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }
}
