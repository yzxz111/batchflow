package com.batchflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }
    public Map<String,String> stringMap(String value) {
        try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String,String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("字段映射损坏", e); }
    }
    public List<String> strings(String value) {
        try { return mapper.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("表头数据损坏", e); }
    }
}
