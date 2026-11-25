package com.batchflow;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.batchflow.Models.*;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "app.role", havingValue = "api", matchIfMissing = true)
public class ApiController {
    private static final String CSV_CONTENT_TYPE = "text/csv; charset=UTF-8";
    private static final int EXPORT_BATCH_SIZE = 1000;
    private static final Set<String> TASK_STATUSES = Set.of(
            "QUEUED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED"
    );

    private static final RowMapper<RowError> ROW_ERROR_MAPPER = (rs, rowNum) -> new RowError(
            rs.getLong("row_number"),
            rs.getString("sku"),
            rs.getString("reason"),
            rs.getString("raw_data")
    );

    private static final RowMapper<Product> PRODUCT_MAPPER = (rs, rowNum) -> new Product(
            rs.getString("id"),
            rs.getString("sku"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getBigDecimal("price"),
            rs.getInt("stock"),
            rs.getString("source_task_id"),
            TaskRepository.instant(rs, "created_at")
    );

    private final UploadService uploads;
    private final TaskService tasks;
    private final TaskRepository repo;
    private final JdbcTemplate jdbc;
    private final String queueMode;

    public ApiController(
            UploadService uploads,
            TaskService tasks,
            TaskRepository repo,
            JdbcTemplate jdbc,
            @Value("${app.queue-mode:database}") String queueMode
    ) {
        this.uploads = uploads;
        this.tasks = tasks;
        this.repo = repo;
        this.jdbc = jdbc;
        this.queueMode = queueMode;
    }

    @PostMapping("/uploads")
    public Upload upload(@RequestParam("file") MultipartFile file) {
        return uploads.upload(file);
    }

    @GetMapping("/templates/products.csv")
    public ResponseEntity<byte[]> productTemplate() throws IOException {
        StringWriter output = new StringWriter();
        output.write('\uFEFF');

        try (CSVPrinter csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            csv.printRecord("sku", "name", "category", "price", "stock");
            // Valid sample rows
            csv.printRecord("P001", "无线鼠标", "办公", "59.90", "100");
            csv.printRecord("P002", "记事本", "文具", "12.50", "0");
            // Invalid sample rows for validation demo
            csv.printRecord("BAD SKU", "非法商品编码示例", "示例", "10.00", "5");
            csv.printRecord("P003", "", "示例", "10.00", "5");
            csv.printRecord("P004", "零价格示例", "示例", "0", "5");
            csv.printRecord("P005", "价格精度错误示例", "示例", "1.234", "5");
            csv.printRecord("P006", "负库存示例", "示例", "10.00", "-1");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, CSV_CONTENT_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("products-template.csv"))
                .body(output.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> createTask(
            @RequestBody CreateTask input,
            @RequestHeader(name = "Idempotency-Key", required = false) String suppliedKey
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tasks.create(input, suppliedKey));
    }

    @GetMapping("/tasks")
    public Page<Task> listTasks(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        validatePagination(page, size);
        String filterStatus = null;
        if (status != null && !status.isBlank()) {
            if (!TASK_STATUSES.contains(status)) {
                throw ApiException.bad("无效的任务状态");
            }
            filterStatus = status;
        }
        return repo.list(q, filterStatus, page, size);
    }

    @GetMapping("/tasks/{id}")
    public TaskDetail taskDetail(@PathVariable("id") String id) {
        Task task = repo.get(id);
        return new TaskDetail(task, repo.events(id));
    }

    @PostMapping("/tasks/{id}/retry")
    public Task retryTask(@PathVariable("id") String id) {
        return tasks.retry(id);
    }

    @PostMapping("/tasks/{id}/cancel")
    public Task cancelTask(@PathVariable("id") String id) {
        return tasks.cancel(id);
    }

    @GetMapping("/tasks/{id}/errors")
    public Page<RowError> taskErrors(
            @PathVariable("id") String id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        validatePagination(page, size);
        return repo.errors(id, page, size);
    }

    @GetMapping("/tasks/{id}/errors.csv")
    public void exportTaskErrors(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        repo.get(id);

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(CSV_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment("errors-" + id + ".csv"));

        try (
                OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
                CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT)
        ) {
            writer.write('\uFEFF');
            csv.printRecord("行号", "商品编码", "错误原因", "原始数据");

            long lastRow = 0;
            while (true) {
                List<RowError> batch = jdbc.query(
                        """
                        SELECT row_number, sku, reason, raw_data
                        FROM row_errors
                        WHERE task_id = ? AND row_number > ?
                        ORDER BY row_number
                        LIMIT ?
                        """,
                        ROW_ERROR_MAPPER, id, lastRow, EXPORT_BATCH_SIZE
                );
                if (batch.isEmpty()) break;

                for (RowError error : batch) {
                    csv.printRecord(
                            error.rowNumber(),
                            CsvFiles.safeCell(error.sku()),
                            CsvFiles.safeCell(error.reason()),
                            CsvFiles.safeCell(error.rawData())
                    );
                }

                lastRow = batch.getLast().rowNumber();
                csv.flush();
                if (batch.size() < EXPORT_BATCH_SIZE) break;
            }
        }
    }

    @GetMapping("/overview")
    public Overview overview() {
        List<WorkerView> workers = jdbc.query(
                """
                SELECT id,
                       CASE WHEN last_seen_at >= clock_timestamp() - interval '15 seconds' THEN 'ONLINE' ELSE 'OFFLINE' END AS status,
                       last_seen_at,
                       current_task_id
                FROM workers
                ORDER BY last_seen_at DESC NULLS LAST, id
                """,
                (rs, rowNum) -> new WorkerView(
                        rs.getString("id"),
                        rs.getString("status"),
                        TaskRepository.instant(rs, "last_seen_at"),
                        rs.getString("current_task_id")
                )
        );

        return jdbc.queryForObject(
                """
                SELECT count(*) AS total_tasks,
                       count(*) FILTER (WHERE status = 'RUNNING') AS running_tasks,
                       count(*) FILTER (WHERE status = 'QUEUED') AS queued_tasks,
                       count(*) FILTER (WHERE status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')) AS completed_tasks,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed_tasks,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_tasks,
                       coalesce(sum(processed_rows), 0) AS processed_rows,
                       coalesce(sum(success_rows), 0) AS success_rows,
                       coalesce(sum(error_rows), 0) AS error_rows,
                       (SELECT count(*) FROM products) AS product_count
                FROM tasks
                """,
                (rs, rowNum) -> new Overview(
                        rs.getLong("total_tasks"),
                        rs.getLong("running_tasks"),
                        rs.getLong("queued_tasks"),
                        rs.getLong("completed_tasks"),
                        rs.getLong("failed_tasks"),
                        rs.getLong("cancelled_tasks"),
                        rs.getLong("processed_rows"),
                        rs.getLong("success_rows"),
                        rs.getLong("error_rows"),
                        rs.getLong("product_count"),
                        queueMode,
                        workers
                )
        );
    }

    @GetMapping("/products")
    public Page<Product> products(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        validatePagination(page, size);

        String where = "";
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where = """
                     WHERE strpos(lower(sku), lower(?)) > 0
                        OR strpos(lower(name), lower(?)) > 0
                        OR strpos(lower(coalesce(category, '')), lower(?)) > 0
                    """;
            args.add(q);
            args.add(q);
            args.add(q);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM products " + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);

        List<Product> items = jdbc.query(
                "SELECT id, sku, name, category, price, stock, source_task_id, created_at FROM products "
                        + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                PRODUCT_MAPPER,
                args.toArray()
        );

        return new Page<>(items, total == null ? 0L : total, page, size);
    }

    private static void validatePagination(int page, int size) {
        if (page < 0) throw ApiException.bad("page 必须大于或等于 0");
        if (size < 1 || size > 100) throw ApiException.bad("size 必须在 1 到 100 之间");
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }
}
