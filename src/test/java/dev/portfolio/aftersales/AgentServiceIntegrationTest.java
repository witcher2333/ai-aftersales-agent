package dev.portfolio.aftersales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AgentServiceIntegrationTest {
    @Autowired Store store;
    @Autowired PolicyService policies;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearCases() {
        jdbc.update("DELETE FROM analysis_results");
        jdbc.update("DELETE FROM analysis_tasks");
        jdbc.update("DELETE FROM case_events");
        jdbc.update("DELETE FROM after_sale_cases");
    }

    private long newCase(int orderIndex, String type, String reason) {
        long customerId = store.findUser("customer").orElseThrow().id();
        long orderId = store.ordersFor(customerId).stream()
                .filter(order -> order.id() == orderIndex).findFirst().orElseThrow().id();
        return store.createTicket(orderId, customerId, type, reason, "agent-test-" + System.nanoTime());
    }

    private String toolReply(String... names) throws Exception {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(Map.of("id", "call-" + i, "type", "function",
                    "function", Map.of("name", names[i], "arguments", "{}")));
        }
        return mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("role", "assistant", "tool_calls", calls)))));
    }

    private String finalReply(String assessment, List<String> missing, List<String> conflicts,
                              List<String> cited) throws Exception {
        Map<String, Object> output = Map.of(
                "category", "物流异常", "assessment", assessment,
                "recommendation", "请客服核对订单、物流与规则后再决定。",
                "missingInformation", missing, "conflicts", conflicts, "evidenceToolNames", cited);
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                Map.of("role", "assistant", "content", mapper.writeValueAsString(output))))));
    }

    private AgentService agent(FakeModel model) {
        return new AgentService(store, policies, mapper, model.url(), "test-key", "test-model", 5);
    }

    @Test
    void normalCaseUsesStrictSchemaAndOnlyScopedEvidence() throws Exception {
        long id = newCase(1, "RETURN", "耳机破损");
        try (FakeModel model = new FakeModel(
                toolReply("get_order", "get_logistics", "get_policies", "find_similar_cases"),
                finalReply("READY", List.of(), List.of(),
                        List.of("get_order", "get_logistics", "get_policies")))) {
            AgentResult result = agent(model).analyze(id);
            JsonNode evidence = mapper.readTree(result.evidenceJson());
            assertEquals("READY", evidence.path("assessment").asText());
            assertEquals(4, evidence.path("queries").size());
            assertEquals(2, model.requests.size());
            JsonNode first = model.requests.get(0);
            assertEquals("json_schema", first.path("response_format").path("type").asText());
            assertTrue(first.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals(4, first.path("tools").size());
            first.path("tools").forEach(tool -> assertTrue(tool.path("function").path("strict").asBoolean()));
            assertTrue(model.requests.get(1).toString().contains("tool_call_id"));
            assertTrue(result.evidenceJson().contains("EARPHONE-01") || result.evidenceJson().contains("无线耳机"));
        }
    }

    @Test
    void missingInformationIsPreservedWithRelevantPolicyEvidence() throws Exception {
        long id = newCase(2, "LOGISTICS", "物流一直没有更新");
        try (FakeModel model = new FakeModel(
                toolReply("get_order", "get_logistics", "get_policies"),
                finalReply("MISSING_INFORMATION", List.of("预计送达时间"), List.of(),
                        List.of("get_order", "get_logistics", "get_policies")))) {
            JsonNode evidence = mapper.readTree(agent(model).analyze(id).evidenceJson());
            assertEquals("MISSING_INFORMATION", evidence.path("assessment").asText());
            assertEquals("预计送达时间", evidence.path("missingInformation").get(0).asText());
            String policyResult = evidence.path("queries").get(2).path("result").asText();
            assertTrue(policyResult.contains("DELAY"));
            assertFalse(policyResult.contains("DAMAGE"));
        }
    }

    @Test
    void conflictingDescriptionIsFlaggedWithoutChangingCaseState() throws Exception {
        long id = newCase(2, "LOGISTICS", "我已经签收，但物流显示运输中");
        try (FakeModel model = new FakeModel(
                toolReply("get_order", "get_logistics", "get_policies"),
                finalReply("DATA_CONFLICT", List.of(), List.of("用户声称已签收，订单物流仍为运输中"),
                        List.of("get_order", "get_logistics")))) {
            JsonNode evidence = mapper.readTree(agent(model).analyze(id).evidenceJson());
            assertEquals("DATA_CONFLICT", evidence.path("assessment").asText());
            assertEquals(1, evidence.path("conflicts").size());
            assertEquals("SUBMITTED", store.ticket(id).orElseThrow().status());
        }
    }

    @Test
    void rejectsUnauthorizedToolAndInventedEvidence() throws Exception {
        long id = newCase(1, "RETURN", "耳机破损");
        try (FakeModel model = new FakeModel(toolReply("approve_refund"))) {
            assertThrows(IllegalStateException.class, () -> agent(model).analyze(id));
            assertEquals(1, model.requests.size());
        }
        try (FakeModel model = new FakeModel(
                toolReply("get_order", "get_logistics", "get_policies"),
                finalReply("READY", List.of(), List.of(),
                        List.of("get_order", "get_logistics", "get_policies", "find_similar_cases")))) {
            assertThrows(IllegalStateException.class, () -> agent(model).analyze(id));
        }
    }

    @Test
    void rejectsModelChosenOrderIdAndMalformedFinalOutput() throws Exception {
        long id = newCase(1, "RETURN", "耳机破损");
        String chosenOrder = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "invalid-args", "type", "function",
                        "function", Map.of("name", "get_order", "arguments", "{\"orderId\":999}"))))))));
        try (FakeModel model = new FakeModel(chosenOrder)) {
            assertThrows(IllegalStateException.class, () -> agent(model).analyze(id));
        }
        String malformed = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                Map.of("role", "assistant", "content", "{\"category\":\"物流异常\"}")))));
        try (FakeModel model = new FakeModel(toolReply("get_order", "get_logistics", "get_policies"), malformed)) {
            assertThrows(IllegalStateException.class, () -> agent(model).analyze(id));
        }
    }

    @Test
    void stopsAfterFiveToolCallingRounds() throws Exception {
        long id = newCase(1, "RETURN", "耳机破损");
        List<String> replies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replies.add(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "tool_calls",
                            List.of(Map.of("id", "round-" + i, "type", "function",
                                    "function", Map.of("name", "get_order", "arguments", "{}")))))))));
        }
        try (FakeModel model = new FakeModel(replies.toArray(String[]::new))) {
            assertThrows(IllegalStateException.class, () -> agent(model).analyze(id));
            assertEquals(5, model.requests.size());
        }
    }

    @Test
    void historicalCasesStayWithinCurrentCustomerAndSku() {
        long customerId = store.findUser("customer").orElseThrow().id();
        long earphoneOrder = store.ordersFor(customerId).stream()
                .filter(order -> "EARPHONE-01".equals(order.sku())).findFirst().orElseThrow().id();
        long keyboardOrder = store.ordersFor(customerId).stream()
                .filter(order -> "KEYBOARD-02".equals(order.sku())).findFirst().orElseThrow().id();
        long otherCustomer = store.insert("INSERT INTO app_users(username,password_hash,role) VALUES (?,?,?)",
                "history-isolation-test", "unused", "CUSTOMER");
        long otherOrder = store.insert("INSERT INTO customer_orders(customer_id,item_name,sku,amount_cents,order_status,logistics_status) VALUES (?,?,?,?,?,?)",
                otherCustomer, "无线耳机", "EARPHONE-01", 29900, "PAID", "DELIVERED");
        try {
            long relevant = store.createTicket(earphoneOrder, customerId, "RETURN", "同商品已结案", "history-relevant");
            jdbc.update("UPDATE after_sale_cases SET status='CLOSED' WHERE id=?", relevant);
            long otherSku = store.createTicket(keyboardOrder, customerId, "LOGISTICS", "不同商品", "history-other-sku");
            jdbc.update("UPDATE after_sale_cases SET status='CLOSED' WHERE id=?", otherSku);
            long otherUser = store.createTicket(otherOrder, otherCustomer, "RETURN", "其他客户", "history-other-user");
            jdbc.update("UPDATE after_sale_cases SET status='CLOSED' WHERE id=?", otherUser);
            store.createTicket(earphoneOrder, customerId, "RETURN", "尚未处理", "history-active");
            long current = store.createTicket(earphoneOrder, customerId, "RETURN", "当前工单", "history-current");

            List<CaseRecord> matches = store.similarCases(customerId, "EARPHONE-01", current);
            assertEquals(List.of(relevant), matches.stream().map(CaseRecord::id).toList());
        } finally {
            jdbc.update("DELETE FROM after_sale_cases WHERE customer_id=?", otherCustomer);
            jdbc.update("DELETE FROM customer_orders WHERE customer_id=?", otherCustomer);
            jdbc.update("DELETE FROM app_users WHERE id=?", otherCustomer);
        }
    }

    private final class FakeModel implements AutoCloseable {
        private final HttpServer server;
        private final String[] replies;
        private final AtomicInteger index = new AtomicInteger();
        final List<JsonNode> requests = new ArrayList<>();

        FakeModel(String... replies) throws Exception {
            this.replies = replies;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                int next = index.getAndIncrement();
                byte[] bytes = replies[Math.min(next, replies.length - 1)].getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
