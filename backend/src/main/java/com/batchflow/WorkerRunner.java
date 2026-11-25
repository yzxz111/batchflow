package com.batchflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import static com.batchflow.Models.*;

@Component
@ConditionalOnProperty(name="app.role",havingValue="worker")
public class WorkerRunner {
    private static final Logger log=LoggerFactory.getLogger(WorkerRunner.class);
    private final TaskEngine engine;
    private final JdbcTemplate jdbc;
    private final String queueMode;
    private final String workerId="worker-"+UUID.randomUUID().toString().substring(0,12);
    private final ReentrantLock executionLock=new ReentrantLock();
    private volatile Lease current;
    public WorkerRunner(TaskEngine engine,JdbcTemplate jdbc,@Value("${app.queue-mode}") String queueMode) { this.engine=engine;this.jdbc=jdbc;this.queueMode=queueMode; }
    @Scheduled(initialDelay=500,fixedDelay=3000)
    public void heartbeat() {
        Lease active=current;
        try {
            jdbc.update("INSERT INTO workers(id,last_seen_at,current_task_id) VALUES (?,clock_timestamp(),?) ON CONFLICT(id) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at,current_task_id=EXCLUDED.current_task_id",workerId,active==null?null:active.taskId());
            if (active!=null) engine.heartbeat(active);
        } catch (Exception e) { log.warn("Worker 心跳失败: {}",e.getMessage()); }
    }
    @Scheduled(initialDelay=1000,fixedDelayString="${app.worker.poll-ms:1000}")
    public void poll() {
        if (!executionLock.tryLock()) return;
        try {
            runClaim(null,"rabbitmq".equals(queueMode));
        } catch (Exception e) {
            log.error("Worker 轮询异常",e);
        } finally {
            current=null;
            executionLock.unlock();
        }
    }
    public void receive(String taskId) {
        try {
            executionLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker 消费等待被中断",e);
        }
        try {
            runClaim(taskId,false);
        } catch (RuntimeException | Error e) {
            log.error("Worker 消费任务 {} 领取异常，交由 Broker 重新投递",taskId,e);
            pauseBeforeRequeue();
            throw e;
        } finally {
            current=null;
            executionLock.unlock();
        }
    }
    private void runClaim(String taskId,boolean onlyExpired) {
        current=engine.claim(workerId,taskId,onlyExpired);
        if (current!=null) engine.process(current);
    }
    private void pauseBeforeRequeue() {
        try { Thread.sleep(200); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
