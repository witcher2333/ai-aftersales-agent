package dev.portfolio.aftersales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
class Store {
    private final JdbcTemplate jdbc;

    Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<UserRecord> USER = (rs, row) -> new UserRecord(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("role"));
    private static final RowMapper<OrderRecord> ORDER = (rs, row) -> new OrderRecord(
            rs.getLong("id"), rs.getLong("customer_id"), rs.getString("item_name"), rs.getString("sku"),
            rs.getLong("amount_cents"), rs.getString("order_status"), rs.getString("logistics_status"),
            time(rs.getTimestamp("delivered_at")), time(rs.getTimestamp("created_at")));
    private static final RowMapper<CaseRecord> CASE = (rs, row) -> new CaseRecord(
            rs.getLong("id"), rs.getLong("order_id"), rs.getLong("customer_id"), rs.getString("case_type"),
            rs.getString("reason"), rs.getString("status"), rs.getLong("version"),
            rs.getObject("assigned_to") == null ? null : rs.getLong("assigned_to"), rs.getString("idempotency_key"),
            time(rs.getTimestamp("created_at")), time(rs.getTimestamp("updated_at")));
    private static final RowMapper<CaseEvent> EVENT = (rs, row) -> new CaseEvent(
            rs.getLong("id"), rs.getLong("case_id"), rs.getObject("actor_id") == null ? null : rs.getLong("actor_id"),
            rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"),
            rs.getString("note"), time(rs.getTimestamp("created_at")));
    private static final RowMapper<PolicyRecord> POLICY = (rs, row) -> new PolicyRecord(
            rs.getLong("id"), rs.getString("code"), rs.getString("title"), rs.getString("content"),
            rs.getBoolean("active"), time(rs.getTimestamp("updated_at")));
    private static final RowMapper<AnalysisTask> TASK = (rs, row) -> new AnalysisTask(
            rs.getLong("id"), rs.getLong("case_id"), rs.getString("status"), rs.getInt("attempts"),
            time(rs.getTimestamp("next_attempt_at")), rs.getString("last_error"), time(rs.getTimestamp("updated_at")));
    private static final RowMapper<AnalysisRecord> ANALYSIS = (rs, row) -> new AnalysisRecord(
            rs.getLong("id"), rs.getLong("case_id"), rs.getString("category"), rs.getString("recommendation"),
            rs.getString("evidence_json"), rs.getString("model_name"), time(rs.getTimestamp("created_at")));

    private static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("数据库未返回生成的主键");
        return keys.getKey().longValue();
    }

    Optional<UserRecord> findUser(String username) {
        return one("SELECT * FROM app_users WHERE username=?", USER, username);
    }

    List<OrderRecord> ordersFor(long customerId) {
        return jdbc.query("SELECT * FROM customer_orders WHERE customer_id=? ORDER BY id DESC", ORDER, customerId);
    }

    List<OrderRecord> allOrders() {
        return jdbc.query("SELECT * FROM customer_orders ORDER BY id DESC", ORDER);
    }

    Optional<OrderRecord> order(long id) {
        return one("SELECT * FROM customer_orders WHERE id=?", ORDER, id);
    }

    Optional<OrderRecord> lockOrder(long id) {
        return one("SELECT * FROM customer_orders WHERE id=? FOR UPDATE", ORDER, id);
    }

    Optional<CaseRecord> ticket(long id) {
        return one("SELECT * FROM after_sale_cases WHERE id=?", CASE, id);
    }

    Optional<CaseRecord> ticketByKey(long customerId, String key) {
        return one("SELECT * FROM after_sale_cases WHERE customer_id=? AND idempotency_key=?", CASE, customerId, key);
    }

    List<CaseRecord> ticketsFor(long customerId) {
        return jdbc.query("SELECT * FROM after_sale_cases WHERE customer_id=? ORDER BY id DESC", CASE, customerId);
    }

    List<CaseRecord> allTickets() {
        return jdbc.query("SELECT * FROM after_sale_cases ORDER BY id DESC", CASE);
    }

    boolean hasActiveCase(long orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND status IN ('SUBMITTED','IN_REVIEW')", Integer.class, orderId);
        return count != null && count > 0;
    }

    long createTicket(long orderId, long customerId, String type, String reason, String key) {
        return insert("INSERT INTO after_sale_cases(order_id,customer_id,case_type,reason,status,idempotency_key) VALUES (?,?,?,?,'SUBMITTED',?)",
                orderId, customerId, type, reason, key);
    }

    boolean transition(long id, String from, String to, long expectedVersion, Long assignee) {
        return jdbc.update("UPDATE after_sale_cases SET status=?,version=version+1,assigned_to=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=? AND version=?",
                to, assignee, id, from, expectedVersion) == 1;
    }

    void event(long caseId, Long actorId, String eventType, String from, String to, String note) {
        jdbc.update("INSERT INTO case_events(case_id,actor_id,event_type,from_status,to_status,note) VALUES (?,?,?,?,?,?)",
                caseId, actorId, eventType, from, to, note);
    }

    List<CaseEvent> events(long caseId) {
        return jdbc.query("SELECT * FROM case_events WHERE case_id=? ORDER BY id", EVENT, caseId);
    }

    List<PolicyRecord> policies() {
        return jdbc.query("SELECT * FROM policies ORDER BY id", POLICY);
    }

    Optional<PolicyRecord> policy(long id) {
        return one("SELECT * FROM policies WHERE id=?", POLICY, id);
    }

    void updatePolicy(long id, String content, boolean active) {
        jdbc.update("UPDATE policies SET content=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", content, active, id);
    }

    void createTask(long caseId) {
        jdbc.update("INSERT INTO analysis_tasks(case_id,status) VALUES (?,'PENDING')", caseId);
    }

    Optional<AnalysisTask> task(long caseId) {
        return one("SELECT * FROM analysis_tasks WHERE case_id=?", TASK, caseId);
    }

    List<AnalysisTask> readyTasks() {
        return jdbc.query("SELECT * FROM analysis_tasks WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 5", TASK);
    }

    boolean claimTask(long id) {
        return jdbc.update("UPDATE analysis_tasks SET status='RUNNING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP", id) == 1;
    }

    void taskDone(long id) {
        jdbc.update("UPDATE analysis_tasks SET status='DONE',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    void taskFailed(long id, int attempts, String error) {
        String status = attempts >= 3 ? "FAILED" : "PENDING";
        LocalDateTime next = LocalDateTime.now().plusSeconds((long) Math.pow(2, attempts) * 10);
        jdbc.update("UPDATE analysis_tasks SET status=?,next_attempt_at=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, Timestamp.valueOf(next), error.substring(0, Math.min(error.length(), 1000)), id);
    }

    void retryTask(long caseId) {
        jdbc.update("UPDATE analysis_tasks SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE case_id=? AND status='FAILED'", caseId);
    }

    void resetStaleTasks() {
        jdbc.update("UPDATE analysis_tasks SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,last_error='任务中断后恢复',updated_at=CURRENT_TIMESTAMP WHERE status='RUNNING' AND updated_at < ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)));
    }

    Optional<AnalysisRecord> analysis(long caseId) {
        return one("SELECT * FROM analysis_results WHERE case_id=?", ANALYSIS, caseId);
    }

    void saveAnalysis(long caseId, AgentResult result) {
        int count = jdbc.update("UPDATE analysis_results SET category=?,recommendation=?,evidence_json=?,model_name=?,created_at=CURRENT_TIMESTAMP WHERE case_id=?",
                result.category(), result.recommendation(), result.evidenceJson(), result.modelName(), caseId);
        if (count == 0) jdbc.update("INSERT INTO analysis_results(case_id,category,recommendation,evidence_json,model_name) VALUES (?,?,?,?,?)",
                caseId, result.category(), result.recommendation(), result.evidenceJson(), result.modelName());
    }

    List<CaseRecord> similarCases(long orderId, long excludedCaseId) {
        return jdbc.query("SELECT * FROM after_sale_cases WHERE order_id<>? AND id<>? AND status IN ('APPROVED','REJECTED','CLOSED') ORDER BY id DESC LIMIT 5",
                CASE, orderId, excludedCaseId);
    }

    int count(String table) {
        // Only called with a fixed, internal table name during demo seeding.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
