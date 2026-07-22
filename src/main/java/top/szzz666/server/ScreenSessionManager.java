package top.szzz666.server;

import top.szzz666.ssh.ConnectionManager;
import top.szzz666.ssh.SshConnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScreenSessionManager {
    private static final Map<String, Map<String, ScreenSession>> sessions = new ConcurrentHashMap<>();
    private static final Pattern SESSION_LINE = Pattern.compile("(?m)\\b\\d+\\.(webssh-[a-f0-9]{12})\\b");

    private ScreenSessionManager() {}

    public static Map<String, Object> start(String connectionId, String path, String requestedLabel, boolean autoExit) throws Exception {
        SshConnection connection = ConnectionManager.require(connectionId);
        String name = "webssh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean runsFile = path != null && !path.isBlank();
        String label = runsFile ? baseName(path) : safeLabel(requestedLabel);
        String script = runsFile
                ? "cd " + shellQuote(parentPath(path)) + " && " + shellQuote(path)
                    + "; status=$?; printf '\\n[webssh] program exited with status: %s\\n' \"$status\"; "
                    + (autoExit ? "exit \"$status\"" : "exec \"${SHELL:-/bin/sh}\" -l")
                : "exec \"${SHELL:-/bin/sh}\" -l";
        try {
            connection.exec("command -v screen >/dev/null 2>&1 || { echo 'screen is not installed on the remote host' >&2; exit 127; }; "
                    + "mkdir -p \"$HOME/.webssh/screen-sessions\" && "
                    + "printf '%s' " + shellQuote(runsFile ? path : "") + " > \"$HOME/.webssh/screen-sessions/" + name + ".path\" && "
                    + "printf '%s' " + shellQuote(label) + " > \"$HOME/.webssh/screen-sessions/" + name + ".label\" && "
                    + "screen -DmS " + shellQuote(name) + " sh -lc " + shellQuote(script) + " </dev/null >/dev/null 2>&1", 1_500);
        } catch (IllegalStateException e) {
            // Some SSH servers keep the exec channel open for detached screen children.
            String active = connection.exec("screen -ls 2>/dev/null || true");
            if (!active.contains("." + name)) throw e;
        }
        ScreenSession session = new ScreenSession(name, label, runsFile ? path : "", Instant.now());
        sessions.computeIfAbsent(connectionId, ignored -> new ConcurrentHashMap<>()).put(name, session);
        return session.toMap();
    }

    public static List<Map<String, Object>> list(String connectionId) throws Exception {
        SshConnection connection = ConnectionManager.require(connectionId);
        String output = connection.exec("screen -ls 2>/dev/null || true");
        Map<String, ScreenSession> connectionSessions = sessions.computeIfAbsent(connectionId, ignored -> new ConcurrentHashMap<>());
        Matcher matcher = SESSION_LINE.matcher(output);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (connectionSessions.containsKey(name)) continue;
            String path = connection.exec("cat \"$HOME/.webssh/screen-sessions/" + name + ".path\" 2>/dev/null || true").trim();
            String label = connection.exec("cat \"$HOME/.webssh/screen-sessions/" + name + ".label\" 2>/dev/null || true").trim();
            connectionSessions.put(name, new ScreenSession(name, label.isBlank() ? (path.isBlank() ? "Screen" : baseName(path)) : label, path, Instant.now()));
        }
        connectionSessions.entrySet().removeIf(entry -> !output.contains("." + entry.getKey()));
        if (connectionSessions.isEmpty()) { sessions.remove(connectionId, connectionSessions); return List.of(); }
        List<ScreenSession> active = new ArrayList<>(connectionSessions.values());
        active.sort(Comparator.comparing(ScreenSession::startedAt));
        return active.stream().map(ScreenSession::toMap).toList();
    }

    public static String attachCommand(String connectionId, String name) {
        validateName(name);
        return "exec screen -x " + shellQuote(name) + "\r";
    }

    public static void kill(String connectionId, String name) throws Exception {
        validateName(name);
        SshConnection connection = ConnectionManager.require(connectionId);
        connection.exec("screen -S " + shellQuote(name) + " -X quit 2>/dev/null || true; rm -f \"$HOME/.webssh/screen-sessions/" + name + ".path\" \"$HOME/.webssh/screen-sessions/" + name + ".label\"");
        Map<String, ScreenSession> connectionSessions = sessions.get(connectionId);
        if (connectionSessions != null) connectionSessions.remove(name);
    }

    public static void removeConnection(String connectionId) { sessions.remove(connectionId); }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static void validateName(String name) { if (name == null || !name.matches("webssh-[a-f0-9]{12}")) throw new ApiException(400, "INVALID_SCREEN_SESSION", "Invalid screen session name"); }
    private static String safeLabel(String label) { String value = label == null ? "Screen" : label.replaceAll("[\\x00-\\x1f]", "").trim(); return value.isBlank() ? "Screen" : value.substring(0, Math.min(80, value.length())); }
    private static String baseName(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? path : path.substring(slash + 1); }
    private static String parentPath(String path) { int slash = path.lastIndexOf('/'); return slash <= 0 ? "/" : path.substring(0, slash); }

    private record ScreenSession(String name, String label, String path, Instant startedAt) {
        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name); value.put("label", label); value.put("path", path); value.put("startedAt", startedAt.toString());
            return value;
        }
    }
}
