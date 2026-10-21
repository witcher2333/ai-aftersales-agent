CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL
);

CREATE TABLE customer_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    item_name VARCHAR(200) NOT NULL,
    sku VARCHAR(80) NOT NULL,
    amount_cents BIGINT NOT NULL,
    order_status VARCHAR(30) NOT NULL,
    logistics_status VARCHAR(30) NOT NULL,
    delivered_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES app_users(id)
);
CREATE INDEX idx_orders_customer ON customer_orders(customer_id, id);

CREATE TABLE after_sale_cases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    case_type VARCHAR(30) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    assigned_to BIGINT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_case_order FOREIGN KEY (order_id) REFERENCES customer_orders(id),
    CONSTRAINT fk_case_customer FOREIGN KEY (customer_id) REFERENCES app_users(id),
    CONSTRAINT uq_case_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_cases_order ON after_sale_cases(order_id, status);
CREATE INDEX idx_cases_status ON after_sale_cases(status, id);

CREATE TABLE case_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NULL,
    note VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_case FOREIGN KEY (case_id) REFERENCES after_sale_cases(id)
);

CREATE TABLE policies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(3000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analysis_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1000) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_case FOREIGN KEY (case_id) REFERENCES after_sale_cases(id)
);
CREATE INDEX idx_tasks_ready ON analysis_tasks(status, next_attempt_at);

CREATE TABLE analysis_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL UNIQUE,
    category VARCHAR(100) NOT NULL,
    recommendation VARCHAR(3000) NOT NULL,
    evidence_json VARCHAR(6000) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_analysis_case FOREIGN KEY (case_id) REFERENCES after_sale_cases(id)
);
