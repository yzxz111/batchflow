package com.batchflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.batchflow.Models.*;

@Service
public class UploadService {
    private final JdbcTemplate jdbc;
    private final Json json;
    private final CsvFiles csv;
    private final Path storage;
    public UploadService(JdbcTemplate jdbc, Json json, CsvFiles csv, @Value("${app.storage-dir}") String storage) {
        this.jdbc = jdbc; this.json = json; this.csv = csv;
        this.storage = Path.of(storage).toAbsolutePath().normalize();
    }
    public Upload upload(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024) throw ApiException.bad("请选择非空且不超过 50 MiB 的 CSV 文件");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("upload.csv").replace('\\','/');
        String filename = original.substring(original.lastIndexOf('/') + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv") || filename.length() > 255) throw ApiException.bad("仅支持文件名长度不超过 255 的 .csv 文件");
        String id = UUID.randomUUID().toString();
        Path path = storage.resolve(id + ".csv");
        try {
            Files.createDirectories(storage);
            try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; long size = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    size += n;
                    if (size > 50L * 1024 * 1024) throw ApiException.bad("CSV 超过 50 MiB 限制");
                    out.write(buffer, 0, n);
                }
            }
            List<String> headers;
            List<Map<String,String>> preview = new ArrayList<>(); long count = 0;
            try (var parser = csv.open(path)) {
                headers = parser.getHeaderNames(); csv.validateHeaders(headers);
                for (var row : parser) {
                    csv.validateRecordSize(row);
                    if (++count > CsvFiles.MAX_ROWS) throw ApiException.bad("CSV 最多支持 1,000,000 行数据");
                    if (preview.size() < 5) preview.add(csv.values(row, headers));
                }
            }
            if (count == 0) throw ApiException.bad("CSV 只有表头，没有可导入的数据");
            jdbc.update("INSERT INTO uploads(id,filename,storage_path,headers_json,total_rows) VALUES (?,?,?,?,?)", id, filename, path.toString(), json.write(headers), count);
            return new Upload(id, filename, headers, preview, count);
        } catch (Exception e) {
            try { Files.deleteIfExists(path); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof ApiException api) throw api;
            if (e instanceof org.springframework.dao.DataAccessException) throw (RuntimeException)e;
            throw ApiException.bad("无法解析 CSV，请检查 UTF-8 编码、引号和表头格式");
        }
    }
}
