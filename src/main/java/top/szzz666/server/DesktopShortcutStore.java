package top.szzz666.server;

import com.google.gson.reflect.TypeToken;
import top.szzz666.tools.JsonUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DesktopShortcutStore {
    private static final Path DIRECTORY = Path.of("/root/.webssh");
    private static final Path FILE = DIRECTORY.resolve("desktop-shortcuts.json");
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    private DesktopShortcutStore() {}

    static synchronized List<Map<String, Object>> load() throws IOException {
        if (!Files.exists(FILE)) return List.of();
        try {
            List<Map<String, Object>> shortcuts = JsonUtil.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), LIST_TYPE);
            return validate(shortcuts);
        } catch (RuntimeException e) {
            throw new IOException("Desktop shortcut configuration is not valid JSON", e);
        }
    }

    static synchronized List<Map<String, Object>> save(String json) throws IOException {
        List<Map<String, Object>> shortcuts;
        try {
            shortcuts = validate(JsonUtil.fromJson(json, LIST_TYPE));
        } catch (RuntimeException e) {
            throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut configuration is invalid");
        }
        Files.createDirectories(DIRECTORY);
        Path temporary = Files.createTempFile(DIRECTORY, "desktop-shortcuts-", ".tmp");
        try {
            Files.writeString(temporary, JsonUtil.toJson(shortcuts), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return shortcuts;
    }

    private static List<Map<String, Object>> validate(List<Map<String, Object>> shortcuts) {
        if (shortcuts == null) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcuts must be a JSON array");
        if (shortcuts.size() > 200) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut limit exceeded");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> shortcut : shortcuts) {
            if (shortcut == null) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut is invalid");
            String id = required(shortcut, "id", 128);
            String path = required(shortcut, "path", 4096);
            String host = required(shortcut, "host", 255);
            String username = required(shortcut, "username", 255);
            String type = required(shortcut, "type", 16);
            if (!type.equals("file") && !type.equals("directory")) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut type is invalid");
            int port;
            try {
                port = ((Number) shortcut.get("port")).intValue();
            } catch (RuntimeException e) {
                throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut port is invalid");
            }
            if (port < 1 || port > 65535) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut port is invalid");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("path", path);
            item.put("type", type);
            item.put("name", optional(shortcut.get("name"), 255));
            item.put("host", host);
            item.put("port", port);
            item.put("username", username);
            result.add(item);
        }
        return result;
    }

    private static String required(Map<String, Object> item, String key, int maxLength) {
        String value = optional(item.get(key), maxLength);
        if (value.isBlank()) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut " + key + " is required");
        return value;
    }

    private static String optional(Object value, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.length() > maxLength) throw new ApiException(400, "INVALID_SHORTCUTS", "Desktop shortcut value is too long");
        return text;
    }
}
