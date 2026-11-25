package com.batchflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Upload(String uploadId, String filename, List<String> headers, List<Map<String,String>> previewRows, long totalRows) {}
    public record CreateTask(String name, String uploadId, Map<String,String> mapping, Integer batchSize, Integer maxAttempts) {}
    public record Task(String id, String name, String filename, String status, long totalRows, long processedRows,
                       long successRows, long errorRows, double progress, int batchSize, int attempt, int maxAttempts,
                       long checkpoint, String workerId, Instant createdAt, Instant startedAt, Instant finishedAt,
                       Instant lastHeartbeatAt, String lastError) {}
    public record TaskDetail(String id, String name, String filename, String status, long totalRows, long processedRows,
                             long successRows, long errorRows, double progress, int batchSize, int attempt, int maxAttempts,
                             long checkpoint, String workerId, Instant createdAt, Instant startedAt, Instant finishedAt,
                             Instant lastHeartbeatAt, String lastError, List<Event> events) {
        public TaskDetail(Task task, List<Event> events) {
            this(task.id(), task.name(), task.filename(), task.status(), task.totalRows(), task.processedRows(),
                    task.successRows(), task.errorRows(), task.progress(), task.batchSize(), task.attempt(), task.maxAttempts(),
                    task.checkpoint(), task.workerId(), task.createdAt(), task.startedAt(), task.finishedAt(),
                    task.lastHeartbeatAt(), task.lastError(), List.copyOf(events));
        }
    }
    public record Event(long id, String type, String message, Instant createdAt) {}
    public record RowError(long rowNumber, String sku, String reason, String rawData) {}
    public record Product(String id, String sku, String name, String category, BigDecimal price, int stock, String sourceTaskId, Instant createdAt) {}
    public record WorkerView(String id, String status, Instant lastSeenAt, String currentTaskId) {}
    public record Overview(long totalTasks, long runningTasks, long queuedTasks, long completedTasks, long failedTasks,
                           long cancelledTasks, long processedRows, long successRows, long errorRows, long productCount,
                           String queueMode, List<WorkerView> workers) {}
    public record ProductInput(String sku, String name, String category, BigDecimal price, int stock) {}
    public record RowInput(long rowNumber, Map<String,String> values, boolean consistent) {}
    public record Lease(String taskId, String workerId, long token, long checkpoint, int batchSize, String path, Map<String,String> mapping) {}
}
