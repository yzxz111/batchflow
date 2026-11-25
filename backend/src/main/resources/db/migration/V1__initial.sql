CREATE TABLE uploads (
 id varchar(36) PRIMARY KEY, filename varchar(255) NOT NULL,
 storage_path text NOT NULL, headers_json text NOT NULL,
 total_rows bigint NOT NULL CHECK(total_rows > 0), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE tasks (
 id varchar(36) PRIMARY KEY, name varchar(120) NOT NULL, upload_id varchar(36) NOT NULL REFERENCES uploads(id),
 filename varchar(255) NOT NULL, mapping_json text NOT NULL, status varchar(32) NOT NULL,
 total_rows bigint NOT NULL, processed_rows bigint NOT NULL DEFAULT 0,
 success_rows bigint NOT NULL DEFAULT 0, error_rows bigint NOT NULL DEFAULT 0,
 checkpoint bigint NOT NULL DEFAULT 0, batch_size integer NOT NULL, attempt integer NOT NULL DEFAULT 0,
 max_attempts integer NOT NULL, lease_token bigint NOT NULL DEFAULT 0, worker_id varchar(120),
 lease_until timestamptz, last_heartbeat_at timestamptz, available_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, finished_at timestamptz, last_error text,
 idempotency_key varchar(128) NOT NULL UNIQUE, request_hash varchar(64) NOT NULL,
 CHECK(processed_rows = success_rows + error_rows), CHECK(checkpoint = processed_rows),
 CHECK(processed_rows BETWEEN 0 AND total_rows), CHECK(batch_size BETWEEN 10 AND 1000),
 CHECK(max_attempts BETWEEN 1 AND 5),
 CHECK(status IN ('QUEUED','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED','CANCELLED'))
);
CREATE INDEX idx_tasks_claim ON tasks(status, available_at, lease_until, created_at);
CREATE TABLE task_events (
 id bigserial PRIMARY KEY, task_id varchar(36) NOT NULL REFERENCES tasks(id),
 type varchar(40) NOT NULL, message text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_task ON task_events(task_id,id);
CREATE TABLE products (
 id varchar(36) PRIMARY KEY, sku varchar(64) NOT NULL UNIQUE, name varchar(200) NOT NULL,
 category varchar(100) NOT NULL, price numeric(10,2) NOT NULL CHECK(price > 0),
 stock integer NOT NULL CHECK(stock >= 0), source_task_id varchar(36) NOT NULL REFERENCES tasks(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE row_errors (
 task_id varchar(36) NOT NULL REFERENCES tasks(id), row_number bigint NOT NULL,
 sku text NOT NULL, reason text NOT NULL, raw_data text NOT NULL,
 PRIMARY KEY(task_id,row_number)
);
CREATE TABLE workers (
 id varchar(120) PRIMARY KEY, last_seen_at timestamptz NOT NULL DEFAULT now(), current_task_id varchar(36)
);
CREATE TABLE task_outbox (
 id bigserial PRIMARY KEY, task_id varchar(36) NOT NULL REFERENCES tasks(id),
 created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error text
);
CREATE INDEX idx_outbox_pending ON task_outbox(next_attempt_at,id) WHERE published_at IS NULL;
