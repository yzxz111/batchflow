package com.batchflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.batchflow.Models.*;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final TaskRepository repo;
    private final TransactionTemplate tx;
    private final Json json;
    public TaskService(JdbcTemplate jdbc,TaskRepository repo,TransactionTemplate tx,Json json) { this.jdbc=jdbc;this.repo=repo;this.tx=tx;this.json=json; }
    public Task create(CreateTask input,String suppliedKey) {
        if (input.name()==null || input.name().isBlank() || input.name().strip().length()>120) throw ApiException.bad("任务名称必填，且不能超过 120 字符");
        int batch = input.batchSize()==null ? 200 : input.batchSize();
        int max = input.maxAttempts()==null ? 3 : input.maxAttempts();
        if (batch<10 || batch>1000 || max<1 || max>5) throw ApiException.bad("批次大小为 10~1000，最大尝试次数为 1~5");
        Map<String,String> mapping = new TreeMap<>();
        if (input.mapping()!=null) input.mapping().forEach((k,v) -> { if (v!=null && !v.isBlank()) mapping.put(k,v); });
        if (!Set.of("sku","name","category","price","stock").containsAll(mapping.keySet())) throw ApiException.bad("存在不支持的目标字段");
        for (String field : List.of("sku","name","price","stock")) if (!mapping.containsKey(field)) throw ApiException.bad("请设置 " + field + " 的字段映射");
        if (new HashSet<>(mapping.values()).size()!=mapping.size()) throw ApiException.bad("不同目标字段不能重复映射同一 CSV 列");
        String key = suppliedKey==null || suppliedKey.isBlank() ? UUID.randomUUID().toString() : suppliedKey.strip();
        if (key.length()>128) throw ApiException.bad("Idempotency-Key 长度不能超过 128");
        String name = input.name().strip();
        String fingerprint = hash(json.write(List.of(name,Optional.ofNullable(input.uploadId()).orElse(""),mapping,batch,max)));
        return tx.execute(s -> {
            var uploads = jdbc.queryForList("SELECT * FROM uploads WHERE id=?",input.uploadId());
            if (uploads.isEmpty()) throw ApiException.missing("上传文件不存在，请重新上传");
            var upload = uploads.getFirst();
            List<String> headers = json.strings((String)upload.get("headers_json"));
            if (!headers.containsAll(mapping.values())) throw ApiException.bad("字段映射引用了文件中不存在的表头");
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.update("""
                INSERT INTO tasks(id,name,upload_id,filename,mapping_json,status,total_rows,batch_size,max_attempts,idempotency_key,request_hash)
                VALUES (?,?,?,?,?,'QUEUED',?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING
                """,id,name,input.uploadId(),upload.get("filename"),json.write(mapping),upload.get("total_rows"),batch,max,key,fingerprint);
            if (inserted==0) {
                var existing = jdbc.queryForMap("SELECT id,request_hash FROM tasks WHERE idempotency_key=?",key);
                if (!fingerprint.equals(existing.get("request_hash"))) throw ApiException.conflict("该幂等键已用于不同的导入请求");
                return repo.get((String)existing.get("id"));
            }
            repo.event(id,"CREATED","任务已创建，等待 Worker 领取"); repo.outbox(id);
            return repo.get(id);
        });
    }
    public Task retry(String id) {
        return tx.execute(s -> {
            lock(id); Task current=repo.get(id);
            if (!current.status().equals("FAILED")) throw ApiException.conflict("只有失败任务可以重试");
            jdbc.update("UPDATE tasks SET status='QUEUED',attempt=0,lease_token=lease_token+1,worker_id=NULL,lease_until=NULL,finished_at=NULL,last_error=NULL,available_at=now() WHERE id=?",id);
            repo.event(id,"RETRIED","手动重试，保留已提交的 " + current.checkpoint() + " 行检查点"); repo.outbox(id);
            return repo.get(id);
        });
    }
    public Task cancel(String id) {
        return tx.execute(s -> {
            lock(id); Task current=repo.get(id);
            if (!Set.of("QUEUED","RUNNING").contains(current.status())) throw ApiException.conflict("任务已结束，不能取消");
            jdbc.update("UPDATE tasks SET status='CANCELLED',lease_token=lease_token+1,lease_until=NULL,worker_id=NULL,finished_at=now() WHERE id=?",id);
            repo.event(id,"CANCELLED","任务已取消，已提交数据保留，后续批次停止"); return repo.get(id);
        });
    }
    private void lock(String id) { if (jdbc.queryForList("SELECT id FROM tasks WHERE id=? FOR UPDATE",id).isEmpty()) throw ApiException.missing("任务不存在"); }
    private static String hash(String data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
