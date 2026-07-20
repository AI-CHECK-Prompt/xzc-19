package com.coldchain.compliance.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;
import java.util.List;

/**
 * JSON 工具，统一 Jackson 配置（处理 Java 8 时间类型）。
 */
public final class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtil() {}

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String s, Class<T> cls) {
        try {
            return MAPPER.readValue(s, cls);
        } catch (Exception e) {
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    public static Map<String, Object> toMap(String s) {
        try {
            return MAPPER.readValue(s, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON → Map 失败", e);
        }
    }

    public static List<Map<String, Object>> toList(String s) {
        try {
            return MAPPER.readValue(s, List.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON → List 失败", e);
        }
    }
}
