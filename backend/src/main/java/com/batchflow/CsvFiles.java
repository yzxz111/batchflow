package com.batchflow;

import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

@Component
public class CsvFiles {
    public static final long MAX_ROWS = 1_000_000;
    public CSVParser open(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        PushbackReader reader = new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder)), 1);
        try {
            int first = reader.read();
            if (first != -1 && first != 0xFEFF) reader.unread(first);
            return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                    .setAllowMissingColumnNames(false).get().parse(reader);
        } catch (Exception e) { reader.close(); throw e; }
    }
    public void validateHeaders(List<String> headers) {
        if (headers.isEmpty() || headers.size() > 100) throw ApiException.bad("CSV 必须包含 1~100 个表头");
        if (headers.stream().anyMatch(h -> h == null || h.isBlank() || h.length() > 200)) throw ApiException.bad("表头不能为空，且长度不能超过 200");
        if (new HashSet<>(headers).size() != headers.size()) throw ApiException.bad("CSV 表头不能重复");
    }
    public Map<String,String> values(CSVRecord row, List<String> headers) {
        Map<String,String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) values.put(headers.get(i), i < row.size() ? row.get(i) : "");
        return values;
    }
    public void validateRecordSize(CSVRecord row) {
        if (row.size() > 100) throw ApiException.bad("CSV 单行最多 100 列");
        for (String cell : row) if (cell.length() > 65536) throw ApiException.bad("CSV 单个字段不能超过 65536 字符");
    }
    public static String safeCell(String value) {
        if (value == null) return "";
        String stripped = value.stripLeading();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) return "'" + value;
        if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) return "'" + value;
        return value;
    }
}
