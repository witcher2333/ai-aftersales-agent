package dev.portfolio.aftersales;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
class DemoData implements ApplicationRunner {
    private final Store store;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final boolean enabled;
    private final String customerPassword;
    private final String agentPassword;

    DemoData(Store store, JdbcTemplate jdbc, PasswordEncoder encoder,
             @Value("${app.demo.enabled}") boolean enabled,
             @Value("${app.demo.customer-password}") String customerPassword,
             @Value("${app.demo.agent-password}") String agentPassword) {
        this.store = store;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.enabled = enabled;
        this.customerPassword = customerPassword;
        this.agentPassword = agentPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || store.count("app_users") > 0) return;
        long customer = store.insert("INSERT INTO app_users(username,password_hash,role) VALUES (?,?,?)",
                "customer", encoder.encode(customerPassword), "CUSTOMER");
        store.insert("INSERT INTO app_users(username,password_hash,role) VALUES (?,?,?)",
                "agent", encoder.encode(agentPassword), "AGENT");
        store.insert("INSERT INTO customer_orders(customer_id,item_name,sku,amount_cents,order_status,logistics_status,delivered_at) VALUES (?,?,?,?,?,?,?)",
                customer, "无线耳机", "EARPHONE-01", 29900, "PAID", "DELIVERED",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)));
        store.insert("INSERT INTO customer_orders(customer_id,item_name,sku,amount_cents,order_status,logistics_status) VALUES (?,?,?,?,?,?)",
                customer, "机械键盘", "KEYBOARD-02", 45900, "PAID", "IN_TRANSIT");
        jdbc.update("INSERT INTO policies(code,title,content,active) VALUES (?,?,?,?)", "DAMAGE", "商品损坏处理",
                "商品签收后7日内发现破损，可提交照片申请退货退款；客服核对订单、签收时间与材料后决定。", true);
        jdbc.update("INSERT INTO policies(code,title,content,active) VALUES (?,?,?,?)", "DELAY", "物流延迟处理",
                "运输中的订单如超过预计送达时间，客服先核查物流，再给出跟进或补偿建议。", true);
        jdbc.update("INSERT INTO policies(code,title,content,active) VALUES (?,?,?,?)", "GENERAL", "售后处理原则",
                "AI仅提供建议。退款或拒绝申请需由客服确认，并记录处理理由。", true);
    }
}
