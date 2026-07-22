package top.szzz666.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * JSON 工具类，基于 Gson
 */
public class JsonUtil {

    /**
     * 构建 GsonBuilder，注册 java.time / java.sql 时间类型的序列化适配器
     * 避免 Java 9+ 模块系统下反射访问 java.time 私有字段失败
     */
    private static GsonBuilder createBuilder() {
        GsonBuilder builder = new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls();
        // 注册 java.time 类型适配器，序列化为字符串
        builder.registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (src, type, ctx) -> new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        builder.registerTypeAdapter(LocalDate.class, (JsonSerializer<LocalDate>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(LocalTime.class, (JsonSerializer<LocalTime>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(ZonedDateTime.class, (JsonSerializer<ZonedDateTime>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(OffsetDateTime.class, (JsonSerializer<OffsetDateTime>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(Timestamp.class, (JsonSerializer<Timestamp>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(java.sql.Date.class, (JsonSerializer<java.sql.Date>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        builder.registerTypeAdapter(java.sql.Time.class, (JsonSerializer<java.sql.Time>) (src, type, ctx) -> new JsonPrimitive(src.toString()));
        return builder;
    }

    private static final Gson GSON = createBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = createBuilder().create();

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static String toCompactJson(Object obj) {
        return COMPACT_GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    /**
     * 解析为 Map
     */
    public static Map<String, Object> toMap(String json) {
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /**
     * 格式化 JSON 字符串
     */
    public static String pretty(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            return GSON.toJson(element);
        } catch (Exception e) {
            return json;
        }
    }
}
