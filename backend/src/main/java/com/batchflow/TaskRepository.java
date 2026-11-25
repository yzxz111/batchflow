package com.batchflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.batchflow.Models.*;

@Repository
public class TaskRepository {
    final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    static final RowMapper<Task> TASK = (rs, n) -> {
        long total = rs.getLong("total_rows"), processed = rs.getLong("processed_rows");
        return new Task(rs.getString("id"),rs.getString("name"),rs.getString("filename"),rs.getString("status"),
                total,processed,rs.getLong("success_rows"),rs.getLong("error_rows"), total == 0 ? 0 : Math.round(processed * 10000.0 / total) / 100.0,
                rs.getInt("batch_size"),rs.getInt("attempt"),rs.getInt("max_attempts"),rs.getLong("checkpoint"),rs.getString("worker_id"),
                instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"finished_at"),instant(rs,"last_heartbeat_at"),rs.getString("last_error"));
    };
    public Task get(String id) {
        return jdbc.query("SELECT * FROM tasks WHERE id=?", TASK, id).stream().findFirst().orElseThrow(() -> ApiException.missing("任务不存在"));
    }
    public void event(String id, String type, String message) {
        jdbc.update("INSERT INTO task_events(task_id,type,message) VALUES (?,?,?)",id,type,message);
    }
    public void outbox(String id) { jdbc.update("INSERT INTO task_outbox(task_id) VALUES (?)",id); }
    public List<Event> events(String id) {
        return jdbc.query("SELECT * FROM (SELECT * FROM task_events WHERE task_id=? ORDER BY id DESC LIMIT 200) e ORDER BY id", (rs,n) -> new Event(rs.getLong("id"),rs.getString("type"),rs.getString("message"),instant(rs,"created_at")),id);
    }
    public Page<Task> list(String q, String status, int page, int size) {
        List<Object> args = new ArrayList<>(); StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q != null && !q.isBlank()) { where.append(" AND (lower(name) LIKE ? OR lower(filename) LIKE ?)"); args.add("%" + q.toLowerCase(Locale.ROOT) + "%"); args.add(args.getLast()); }
        if (status != null && !status.isBlank()) { where.append(" AND status=?"); args.add(status); }
        long total = jdbc.queryForObject("SELECT count(*) FROM tasks" + where,Long.class,args.toArray());
        args.add(size); args.add((long)page * size);
        return new Page<>(jdbc.query("SELECT * FROM tasks" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",TASK,args.toArray()),total,page,size);
    }
    public Page<RowError> errors(String id,int page,int size) {
        get(id);
        long total = jdbc.queryForObject("SELECT count(*) FROM row_errors WHERE task_id=?",Long.class,id);
        var items = jdbc.query("SELECT * FROM row_errors WHERE task_id=? ORDER BY row_number LIMIT ? OFFSET ?",(rs,n) -> new RowError(rs.getLong("row_number"),rs.getString("sku"),rs.getString("reason"),rs.getString("raw_data")),id,size,(long)page*size);
        return new Page<>(items,total,page,size);
    }
}
