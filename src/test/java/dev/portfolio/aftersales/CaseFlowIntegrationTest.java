package dev.portfolio.aftersales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaseFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AnalysisWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired Store store;
    @Autowired PolicyService policies;

    @BeforeEach
    void clearCases() {
        jdbc.update("DELETE FROM analysis_results");
        jdbc.update("DELETE FROM analysis_tasks");
        jdbc.update("DELETE FROM case_events");
        jdbc.update("DELETE FROM after_sale_cases");
    }

    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }

    private JsonNode customerPost(String path, Object request) throws Exception {
        String body = mvc.perform(post(path).with(httpBasic("customer", "customer123"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode agentPost(String path, Object request) throws Exception {
        var action = post(path).with(httpBasic("agent", "agent123"));
        if (request != null) action.contentType(MediaType.APPLICATION_JSON).content(json(request));
        String body = mvc.perform(action).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void customerToAgentFlowAndAnalysis() throws Exception {
        JsonNode created = customerPost("/api/cases", Map.of("orderId", 1, "caseType", "RETURN",
                "reason", "耳机破损", "idempotencyKey", "flow-1"));
        long id = created.path("ticket").path("id").asLong();
        worker.process();
        String detail = mvc.perform(get("/api/cases/" + id).with(httpBasic("customer", "customer123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode analyzed = mapper.readTree(detail);
        org.junit.jupiter.api.Assertions.assertEquals("DONE", analyzed.path("analysisTask").path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("demo-rule-engine", analyzed.path("analysis").path("modelName").asText());

        JsonNode taken = agentPost("/api/cases/" + id + "/take", null);
        org.junit.jupiter.api.Assertions.assertEquals("IN_REVIEW", taken.path("ticket").path("status").asText());
        mvc.perform(post("/api/cases/" + id + "/decision").with(httpBasic("agent", "agent123"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("decision", "APPROVED", "note", "旧页面", "expectedVersion", 0))))
                .andExpect(status().isConflict());
        JsonNode decided = agentPost("/api/cases/" + id + "/decision",
                Map.of("decision", "APPROVED", "note", "已核实材料", "expectedVersion", 1));
        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", decided.path("ticket").path("status").asText());
        JsonNode closed = agentPost("/api/cases/" + id + "/close", null);
        org.junit.jupiter.api.Assertions.assertEquals(4, closed.path("events").size());
    }

    @Test
    void idempotencyAndBusinessValidation() throws Exception {
        Map<String, Object> request = Map.of("orderId", 1, "caseType", "REFUND", "reason", "收到错误商品", "idempotencyKey", "same-key");
        long first = customerPost("/api/cases", request).path("ticket").path("id").asLong();
        long second = customerPost("/api/cases", request).path("ticket").path("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        mvc.perform(post("/api/cases").with(httpBasic("customer", "customer123"))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("orderId", 1, "caseType", "REFUND",
                        "reason", "另一原因", "idempotencyKey", "same-key"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/cases").with(httpBasic("customer", "customer123"))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("orderId", 1, "caseType", "RETURN",
                        "reason", "再开一单", "idempotencyKey", "new-key"))))
                .andExpect(status().isConflict());
    }

    @Test
    void roleAndOrderRules() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cases").with(httpBasic("agent", "agent123"))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("orderId", 1, "caseType", "REFUND",
                        "reason", "不允许客服代建", "idempotencyKey", "agent-key"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases").with(httpBasic("customer", "customer123"))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("orderId", 2, "caseType", "RETURN",
                        "reason", "还在运输中", "idempotencyKey", "invalid-key"))))
                .andExpect(status().isConflict());
        JsonNode ticket = customerPost("/api/cases", Map.of("orderId", 2, "caseType", "LOGISTICS",
                "reason", "物流迟迟不更新", "idempotencyKey", "logistics-key"));
        mvc.perform(post("/api/cases/" + ticket.path("ticket").path("id").asLong() + "/take")
                .with(httpBasic("customer", "customer123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void realModelProtocolCallsOnlyScopedReadTool() throws Exception {
        long caseId = customerPost("/api/cases", Map.of("orderId", 1, "caseType", "RETURN",
                "reason", "商品破损", "idempotencyKey", "tool-test")).path("ticket").path("id").asLong();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> secondRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int count = requests.incrementAndGet();
            String response;
            if (count == 1) {
                response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_order\",\"arguments\":\"{}\"}}]}}]}";
            } else {
                secondRequest.set(body);
                response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"category\\\":\\\"退货申请\\\",\\\"recommendation\\\":\\\"请人工核对破损材料\\\"}\"}}]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AgentService realAgent = new AgentService(store, policies, mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions", "test-key", "test-model", 5);
            AgentResult result = realAgent.analyze(caseId);
            org.junit.jupiter.api.Assertions.assertEquals(2, requests.get());
            org.junit.jupiter.api.Assertions.assertEquals("退货申请", result.category());
            org.junit.jupiter.api.Assertions.assertTrue(secondRequest.get().contains("tool_call_id"));
            org.junit.jupiter.api.Assertions.assertTrue(result.evidenceJson().contains("get_order"));
        } finally {
            server.stop(0);
        }
    }
}
