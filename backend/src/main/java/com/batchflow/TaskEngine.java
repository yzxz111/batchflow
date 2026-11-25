package com.batchflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static com.batchflow.Models.*;

@Service
public class TaskEngine {
    private final JdbcTemplate jdbc;
    private final TaskRepository repo;
    private final TransactionTemplate tx;
    private final Json json;
    private final CsvFiles csv;
    private final ProductValidator validator;
    private final int leaseSeconds;
    private final long batchDelayMs;
    public TaskEngine(JdbcTemplate jdbc,TaskRepository repo,TransactionTemplate tx,Json json,CsvFiles csv,ProductValidator validator,
                      @Value("${app.worker.lease-seconds:15}") int leaseSeconds,@Value("${app.worker.batch-delay-ms:0}") long batchDelayMs) {
        if (leaseSeconds < 5 || leaseSeconds > 300) throw new IllegalArgumentException("租约必须为 5~300 秒");
        if (batchDelayMs < 0 || batchDelayMs > 60000) throw new IllegalArgumentException("批次延迟必须为 0~60000 毫秒");
        this.jdbc=jdbc;this.repo=repo;this.tx=tx;this.json=json;this.csv=csv;this.validator=validator;this.leaseSeconds=leaseSeconds;this.batchDelayMs=batchDelayMs;
    }
    public Lease claim(String workerId,String taskId,boolean onlyExpired) {
        return tx.execute(s -> {
            String eligible = onlyExpired ? "(t.status='RUNNING' AND t.lease_until < clock_timestamp())" : "(t.status='QUEUED' OR (t.status='RUNNING' AND t.lease_until < clock_timestamp()))";
            List<Object> args=new ArrayList<>();
            if (taskId!=null) { eligible += " AND t.id=?"; args.add(taskId); }
            var rows=jdbc.queryForList("SELECT t.*,u.storage_path FROM tasks t JOIN uploads u ON u.id=t.upload_id WHERE " + eligible + " AND t.available_at<=clock_timestamp() ORDER BY t.created_at,t.id LIMIT 1 FOR UPDATE OF t SKIP LOCKED",args.toArray());
            if (rows.isEmpty()) return null;
            var row=rows.getFirst(); String id=(String)row.get("id");
            int attempt=((Number)row.get("attempt")).intValue();
            if (attempt>=((Number)row.get("max_attempts")).intValue()) {
                jdbc.update("UPDATE tasks SET status='FAILED',finished_at=now(),lease_until=NULL,worker_id=NULL,last_error='Worker 失联，已达到最大尝试次数',lease_token=lease_token+1 WHERE id=?",id);
                repo.event(id,"FAILED","Worker 失联，已达到最大尝试次数，可手动重试"); return null;
            }
            long token=((Number)row.get("lease_token")).longValue()+1;
            jdbc.update("""
                UPDATE tasks SET status='RUNNING',attempt=attempt+1,lease_token=?,worker_id=?,lease_until=clock_timestamp()+(? * interval '1 second'),
                last_heartbeat_at=clock_timestamp(),started_at=COALESCE(started_at,now()),finished_at=NULL,last_error=NULL WHERE id=?
                """,token,workerId,leaseSeconds,id);
            String type = row.get("status").equals("RUNNING") ? "RECOVERED" : "STARTED";
            repo.event(id,type,(type.equals("RECOVERED") ? "接管失联任务" : "开始处理") + "，从第 " + row.get("checkpoint") + " 行检查点继续；Worker " + workerId);
            return new Lease(id,workerId,token,((Number)row.get("checkpoint")).longValue(),((Number)row.get("batch_size")).intValue(),(String)row.get("storage_path"),json.stringMap((String)row.get("mapping_json")));
        });
    }
    public void process(Lease lease) {
        try (var parser=csv.open(Path.of(lease.path()))) {
            List<String> headers=parser.getHeaderNames(); csv.validateHeaders(headers);
            List<RowInput> batch=new ArrayList<>(lease.batchSize()); long seen=0;
            for (var record : parser) {
                seen++;
                if (seen<=lease.checkpoint()) continue;
                csv.validateRecordSize(record);
                batch.add(new RowInput(record.getRecordNumber()+1,csv.values(record,headers),record.isConsistent()));
                if (batch.size()>=lease.batchSize()) { commitBatch(lease,batch); batch.clear(); pause(); }
            }
            if (!batch.isEmpty()) { commitBatch(lease,batch); pause(); }
            finish(lease,seen);
        } catch (LeaseLostException ignored) {
            // Another lease owns the task, or cancellation fenced this worker. Never overwrite its state.
        } catch (Exception e) {
            fail(lease,e);
        }
    }
    public void commitBatch(Lease lease,List<RowInput> batch) {
        if (batch.isEmpty()) return;
        tx.executeWithoutResult(s -> {
            Map<String,Object> locked=lockLease(lease);
            long expected=((Number)locked.get("checkpoint")).longValue()+2;
            long successes=0, errors=0;
            for (RowInput row : batch) {
                if (row.rowNumber()!=expected++) throw new IllegalStateException("批次检查点不连续");
                String reason=null;
                ProductInput product=null;
                try {
                    if (!row.consistent()) throw new IllegalArgumentException("CSV 列数与表头不一致");
                    product=validator.validate(row.values(),lease.mapping());
                } catch (IllegalArgumentException invalid) { reason=invalid.getMessage(); }
                if (reason==null) {
                    int inserted=jdbc.update("INSERT INTO products(id,sku,name,category,price,stock,source_task_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT(sku) DO NOTHING",
                            UUID.randomUUID().toString(),product.sku(),product.name(),product.category(),product.price(),product.stock(),lease.taskId());
                    if (inserted==1) { successes++; continue; }
                    reason="SKU 已存在（文件内或历史任务重复）";
                }
                String sku=row.values().getOrDefault(lease.mapping().get("sku"),"");
                jdbc.update("INSERT INTO row_errors(task_id,row_number,sku,reason,raw_data) VALUES (?,?,?,?,?)",
                        lease.taskId(),row.rowNumber(),sku,reason,json.write(row.values())); errors++;
            }
            int updated=jdbc.update("""
                UPDATE tasks SET processed_rows=processed_rows+?,success_rows=success_rows+?,error_rows=error_rows+?,checkpoint=checkpoint+?,
                lease_until=clock_timestamp()+(? * interval '1 second'),last_heartbeat_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND worker_id=? AND status='RUNNING' AND lease_until>clock_timestamp()
                """,batch.size(),successes,errors,batch.size(),leaseSeconds,lease.taskId(),lease.token(),lease.workerId());
            if (updated!=1) throw new LeaseLostException();
        });
    }
    public void finish(Lease lease,long seen) {
        tx.executeWithoutResult(s -> {
            Map<String,Object> row=lockLease(lease);
            if (((Number)row.get("checkpoint")).longValue()!=seen || ((Number)row.get("total_rows")).longValue()!=seen) throw new IllegalStateException("文件记录数与上传时不一致");
            long errorCount=((Number)row.get("error_rows")).longValue();
            String status=errorCount==0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
            int n=jdbc.update("UPDATE tasks SET status=?,finished_at=now(),lease_until=NULL,worker_id=NULL,last_error=NULL WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()",status,lease.taskId(),lease.token());
            if (n!=1) throw new LeaseLostException();
            repo.event(lease.taskId(),"COMPLETED","导入完成：成功 " + row.get("success_rows") + " 行，错误 " + errorCount + " 行");
        });
    }
    public boolean heartbeat(Lease lease) {
        return jdbc.update("UPDATE tasks SET last_heartbeat_at=clock_timestamp(),lease_until=clock_timestamp()+(? * interval '1 second') WHERE id=? AND lease_token=? AND worker_id=? AND status='RUNNING' AND lease_until>clock_timestamp()",leaseSeconds,lease.taskId(),lease.token(),lease.workerId())==1;
    }
    private Map<String,Object> lockLease(Lease lease) {
        var rows=jdbc.queryForList("SELECT *,lease_until>clock_timestamp() AS alive FROM tasks WHERE id=? FOR UPDATE",lease.taskId());
        if (rows.isEmpty()) throw new LeaseLostException();
        var row=rows.getFirst();
        if (!"RUNNING".equals(row.get("status")) || !lease.workerId().equals(row.get("worker_id")) || ((Number)row.get("lease_token")).longValue()!=lease.token() || !Boolean.TRUE.equals(row.get("alive"))) throw new LeaseLostException();
        return row;
    }
    private void fail(Lease lease,Exception error) {
        tx.executeWithoutResult(s -> {
            Map<String,Object> row;
            try { row=lockLease(lease); } catch (LeaseLostException stale) { return; }
            boolean retry=((Number)row.get("attempt")).intValue()<((Number)row.get("max_attempts")).intValue();
            String message=Optional.ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName());
            if (message.length()>2000) message=message.substring(0,2000);
            jdbc.update("UPDATE tasks SET status=?,last_error=?,lease_token=lease_token+1,lease_until=NULL,worker_id=NULL,finished_at=CASE WHEN ? THEN NULL ELSE now() END,available_at=now() WHERE id=?",retry?"QUEUED":"FAILED",message,retry,lease.taskId());
            repo.event(lease.taskId(),retry?"AUTO_RETRY":"FAILED",(retry?"处理异常，自动重新排队：":"处理失败：") + message);
            if (retry) repo.outbox(lease.taskId());
        });
    }
    private void pause() throws InterruptedException { if (batchDelayMs>0) Thread.sleep(batchDelayMs); }
}
