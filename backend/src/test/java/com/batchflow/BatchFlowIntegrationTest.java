package com.batchflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.batchflow.Models.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchFlowIntegrationTest {

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String dbUrl = Optional.ofNullable(System.getenv("BATCHFLOW_TEST_DB_URL"))
                .filter(s -> !s.isBlank())
                .orElse("jdbc:postgresql://127.0.0.1:15439/batchflow_test");
        String dbUser = Optional.ofNullable(System.getenv("BATCHFLOW_TEST_DB_USER"))
                .filter(s -> !s.isBlank())
                .orElse("batchflow");
        String dbPass = Optional.ofNullable(System.getenv("BATCHFLOW_TEST_DB_PASSWORD"))
                .orElse("");

        registry.add("spring.datasource.url", () -> dbUrl);
        registry.add("spring.datasource.username", () -> dbUser);
        registry.add("spring.datasource.password", () -> dbPass);
        registry.add("app.role", () -> "api");
        registry.add("app.queue-mode", () -> "database");
        registry.add("app.storage-dir", () -> storageDir.toAbsolutePath().toString());
        registry.add("app.worker.lease-seconds", () -> "10");
        registry.add("app.worker.batch-delay-ms", () -> "0");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TaskEngine engine;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("DELETE FROM row_errors");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM task_events");
        jdbc.execute("DELETE FROM task_outbox");
        jdbc.execute("DELETE FROM tasks");
        jdbc.execute("DELETE FROM uploads");
        jdbc.execute("DELETE FROM workers");
    }

    @Test
    void healthAndTemplateAndEndToEndImportAndErrorCsvExport() {
        ResponseEntity<HealthController.Health> health = rest.getForEntity("/api/health", HealthController.Health.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertNotNull(health.getBody());
        assertEquals("UP", health.getBody().status());

        ResponseEntity<ApiAdvice.ApiError> methodNotAllowed = rest.exchange("/api/tasks", HttpMethod.PUT, HttpEntity.EMPTY, ApiAdvice.ApiError.class);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, methodNotAllowed.getStatusCode());
        assertNotNull(methodNotAllowed.getBody());
        assertEquals("METHOD_NOT_ALLOWED", methodNotAllowed.getBody().code());

        ResponseEntity<String> template = rest.getForEntity("/api/templates/products.csv", String.class);
        assertEquals(HttpStatus.OK, template.getStatusCode());
        assertNotNull(template.getBody());
        assertTrue(template.getBody().contains("sku,name,category,price,stock"));

        String csv = "\uFEFFsku,name,category,price,stock\r\n"
                + "SKU-100,\"带引号,\n换行商品\",办公,19.90,5\r\n"
                + "=CMD|'/C calc'!A0,危险公式SKU,办公,10.00,1\r\n"
                + "SKU-100,重复SKU不覆盖,办公,99.00,1\r\n"
                + "SKU-200,负价格商品,办公,-5.00,3\r\n";

        Upload upload = uploadCsv("products.csv", csv);
        assertEquals(4, upload.totalRows());
        assertEquals(4, upload.previewRows().size());

        Task created = createTask("导入测试", upload.uploadId(), 10, 3, UUID.randomUUID().toString());
        assertEquals("QUEUED", created.status());

        Lease lease = engine.claim("worker-a", created.id(), false);
        assertNotNull(lease);
        engine.process(lease);

        TaskDetail detail = rest.getForObject("/api/tasks/{id}", TaskDetail.class, created.id());
        assertNotNull(detail);
        assertEquals("COMPLETED_WITH_ERRORS", detail.status());
        assertEquals(4, detail.totalRows());
        assertEquals(4, detail.processedRows());
        assertEquals(1, detail.successRows());
        assertEquals(3, detail.errorRows());
        assertEquals(4, detail.checkpoint());
        assertFalse(detail.events().isEmpty());

        ResponseEntity<Page<RowError>> errorsResp = rest.exchange(
                "/api/tasks/{id}/errors?page=0&size=20",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {},
                created.id()
        );
        assertEquals(HttpStatus.OK, errorsResp.getStatusCode());
        Page<RowError> errorPage = errorsResp.getBody();
        assertNotNull(errorPage);
        assertEquals(3, errorPage.total());
        assertEquals(List.of(3L, 4L, 5L), errorPage.items().stream().map(RowError::rowNumber).toList());

        ResponseEntity<byte[]> errorCsvResp = rest.getForEntity("/api/tasks/{id}/errors.csv", byte[].class, created.id());
        assertEquals(HttpStatus.OK, errorCsvResp.getStatusCode());
        byte[] bytes = errorCsvResp.getBody();
        assertNotNull(bytes);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String exported = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(exported.contains("'=CMD"));

        ResponseEntity<Page<Product>> productsResp = rest.exchange(
                "/api/products?q=SKU-100",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, productsResp.getStatusCode());
        Page<Product> products = productsResp.getBody();
        assertNotNull(products);
        assertEquals(1, products.total());
        assertEquals("带引号,\n换行商品", products.items().getFirst().name());

        Overview overview = rest.getForObject("/api/overview", Overview.class);
        assertNotNull(overview);
        assertEquals(1, overview.totalTasks());
        assertEquals(1, overview.completedTasks());
        assertEquals(1, overview.productCount());
        assertEquals(4, overview.processedRows());
    }

    @Test
    void idempotencyKeyReplaysSameTaskAndRejectsDifferentPayload() {
        String csv = "sku,name,category,price,stock\nIDEM-1,商品1,办公,10.00,2\n";
        Upload upload = uploadCsv("idem.csv", csv);
        String key = UUID.randomUUID().toString();

        Task first = createTask("幂等任务", upload.uploadId(), 10, 3, key);
        Task replay = createTask("幂等任务", upload.uploadId(), 10, 3, key);
        assertEquals(first.id(), replay.id());

        CreateTask different = new CreateTask("不同名称", upload.uploadId(), defaultMapping(), 10, 3);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        ResponseEntity<ApiAdvice.ApiError> conflict = rest.exchange(
                "/api/tasks", HttpMethod.POST, new HttpEntity<>(different, headers), ApiAdvice.ApiError.class
        );
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertNotNull(conflict.getBody());
        assertEquals("CONFLICT", conflict.getBody().code());
    }

    @Test
    void expiredLeaseCanBeClaimedByNewWorkerAndFencesStaleWorker() {
        StringBuilder sb = new StringBuilder("sku,name,category,price,stock\n");
        for (int i = 1; i <= 25; i++) {
            sb.append("LEASE-").append(i).append(",商品").append(i).append(",办公,15.00,5\n");
        }
        Upload upload = uploadCsv("lease.csv", sb.toString());
        Task task = createTask("租约恢复", upload.uploadId(), 10, 3, UUID.randomUUID().toString());

        Lease worker1 = engine.claim("worker-1", task.id(), false);
        assertNotNull(worker1);
        assertEquals(1L, worker1.token());

        List<RowInput> batch1 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            batch1.add(new RowInput(i + 1L, Map.of(
                    "sku", "LEASE-" + i, "name", "商品" + i, "category", "办公", "price", "15.00", "stock", "5"
            ), true));
        }
        engine.commitBatch(worker1, batch1);

        // Simulate worker-1 stalling until lease expires.
        jdbc.update("UPDATE tasks SET lease_until = clock_timestamp() - interval '5 seconds' WHERE id = ?", task.id());

        Lease worker2 = engine.claim("worker-2", task.id(), true);
        assertNotNull(worker2);
        assertEquals(2L, worker2.token());
        assertEquals(10L, worker2.checkpoint());

        // Stale worker-1 must be fenced out on both commitBatch and finish.
        List<RowInput> staleBatch = List.of(new RowInput(12L, Map.of(
                "sku", "LEASE-11", "name", "商品11", "category", "办公", "price", "15.00", "stock", "5"
        ), true));
        assertThrows(LeaseLostException.class, () -> engine.commitBatch(worker1, staleBatch));
        assertThrows(LeaseLostException.class, () -> engine.finish(worker1, 25));
        assertFalse(engine.heartbeat(worker1));

        // New worker-2 resumes from checkpoint 10 and finishes cleanly.
        engine.process(worker2);

        Task finished = rest.getForObject("/api/tasks/{id}", Task.class, task.id());
        assertNotNull(finished);
        assertEquals("COMPLETED", finished.status());
        assertEquals(25, finished.processedRows());
        assertEquals(25, finished.successRows());
        assertEquals(0, finished.errorRows());
        assertEquals(25, finished.checkpoint());

        Long productCount = jdbc.queryForObject("SELECT count(*) FROM products WHERE source_task_id = ?", Long.class, task.id());
        assertEquals(25L, productCount);
    }

    @Test
    void cancelFencesActiveWorkerAndRetryResumesFromCheckpoint() {
        StringBuilder sb = new StringBuilder("sku,name,category,price,stock\n");
        for (int i = 1; i <= 20; i++) {
            sb.append("CTRL-").append(i).append(",控制商品").append(i).append(",办公,20.00,1\n");
        }
        Upload upload = uploadCsv("control.csv", sb.toString());

        // Part 1: Cancel fences worker.
        Task cancelTask = createTask("取消测试", upload.uploadId(), 10, 3, UUID.randomUUID().toString());
        Lease lease = engine.claim("worker-c", cancelTask.id(), false);
        assertNotNull(lease);
        List<RowInput> batch1 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            batch1.add(new RowInput(i + 1L, Map.of(
                    "sku", "CTRL-" + i, "name", "控制商品" + i, "category", "办公", "price", "20.00", "stock", "1"
            ), true));
        }
        engine.commitBatch(lease, batch1);

        ResponseEntity<Task> cancelled = rest.postForEntity("/api/tasks/{id}/cancel", null, Task.class, cancelTask.id());
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertNotNull(cancelled.getBody());
        assertEquals("CANCELLED", cancelled.getBody().status());

        List<RowInput> batch2 = List.of(new RowInput(12L, Map.of(
                "sku", "CTRL-11", "name", "控制商品11", "category", "办公", "price", "20.00", "stock", "1"
        ), true));
        assertThrows(LeaseLostException.class, () -> engine.commitBatch(lease, batch2));
        assertEquals(10L, jdbc.queryForObject("SELECT count(*) FROM products WHERE source_task_id = ?", Long.class, cancelTask.id()));

        // Part 2: Failed task retry preserves checkpoint and finishes remaining rows.
        StringBuilder sb2 = new StringBuilder("sku,name,category,price,stock\n");
        for (int i = 1; i <= 20; i++) {
            sb2.append("RETRY-").append(i).append(",重试商品").append(i).append(",办公,20.00,1\n");
        }
        Upload upload2 = uploadCsv("retry.csv", sb2.toString());
        Task retryTask = createTask("重试测试", upload2.uploadId(), 10, 1, UUID.randomUUID().toString());
        Lease retryLease = engine.claim("worker-r", retryTask.id(), false);
        assertNotNull(retryLease);

        List<RowInput> rBatch1 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rBatch1.add(new RowInput(i + 1L, Map.of(
                    "sku", "RETRY-" + i, "name", "重试商品" + i, "category", "办公", "price", "20.00", "stock", "1"
            ), true));
        }
        engine.commitBatch(retryLease, rBatch1);

        // Delete backing file temporarily to force processing failure with maxAttempts=1 -> FAILED.
        Path savedFile = storageDir.resolve(upload2.uploadId() + ".csv");
        byte[] savedBytes;
        try {
            savedBytes = Files.readAllBytes(savedFile);
            Files.delete(savedFile);
            engine.process(retryLease);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Task failed = rest.getForObject("/api/tasks/{id}", Task.class, retryTask.id());
        assertNotNull(failed);
        assertEquals("FAILED", failed.status());
        assertEquals(10, failed.checkpoint());

        // Restore backing file and manually retry via API.
        try {
            Files.write(savedFile, savedBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ResponseEntity<Task> retried = rest.postForEntity("/api/tasks/{id}/retry", null, Task.class, retryTask.id());
        assertEquals(HttpStatus.OK, retried.getStatusCode());
        assertNotNull(retried.getBody());
        assertEquals("QUEUED", retried.getBody().status());
        assertEquals(0, retried.getBody().attempt());
        assertEquals(10, retried.getBody().checkpoint());

        Lease resumed = engine.claim("worker-r2", retryTask.id(), false);
        assertNotNull(resumed);
        engine.process(resumed);

        Task completed = rest.getForObject("/api/tasks/{id}", Task.class, retryTask.id());
        assertNotNull(completed);
        assertEquals("COMPLETED", completed.status());
        assertEquals(20, completed.successRows());
        assertEquals(20, completed.checkpoint());
    }

    private Upload uploadCsv(String filename, String content) {
        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Upload> response = rest.postForEntity("/api/uploads", new HttpEntity<>(body, headers), Upload.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private Task createTask(String name, String uploadId, int batchSize, int maxAttempts, String key) {
        CreateTask input = new CreateTask(name, uploadId, defaultMapping(), batchSize, maxAttempts);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        ResponseEntity<Task> response = rest.postForEntity("/api/tasks", new HttpEntity<>(input, headers), Task.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static Map<String, String> defaultMapping() {
        return Map.of("sku", "sku", "name", "name", "category", "category", "price", "price", "stock", "stock");
    }
}
