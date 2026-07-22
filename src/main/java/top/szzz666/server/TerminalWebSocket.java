package top.szzz666.server;

import com.google.gson.JsonObject;
import com.jcraft.jsch.ChannelShell;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import top.szzz666.config.MyConfig;
import top.szzz666.ssh.ConnectionManager;
import top.szzz666.tools.JsonUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@WebSocket(maxTextMessageSize = 1024 * 1024)
public class TerminalWebSocket {
    private Session webSocket;
    private ChannelShell shell;
    private OutputStream input;

    @OnWebSocketConnect
    public void onConnect(Session session) {
        webSocket = session;
        try {
            String id = pathId(session, "sessions");
            shell = (ChannelShell) ConnectionManager.require(id).session().openChannel("shell");
            shell.setPtyType("xterm-256color");
            shell.setPtySize(120, 30, 0, 0);
            InputStream output = shell.getInputStream();
            input = shell.getOutputStream();
            shell.connect(MyConfig.connectTimeoutMs);
            send(Map.of("type", "status", "message", "connected"));
            Thread.ofVirtual().name("ssh-terminal-" + id).start(() -> pump(output));
        } catch (ApiException e) {
            send(Map.of("type", "error", "code", e.code(), "message", e.getMessage()));
            session.close(4004, e.code());
        } catch (Exception e) {
            send(Map.of("type", "error", "message", safeMessage(e)));
            session.close(1011, "Unable to open SSH terminal");
        }
    }

    @OnWebSocketMessage
    public void onMessage(String text) {
        try {
            JsonObject message = JsonUtil.fromJson(text, JsonObject.class);
            String type = message.has("type") ? message.get("type").getAsString() : "";
            if ("input".equals(type)) {
                if (input == null) throw new IllegalStateException("Terminal is not ready");
                input.write(message.has("data") ? message.get("data").getAsString().getBytes(StandardCharsets.UTF_8) : new byte[0]);
                input.flush();
            } else if ("resize".equals(type) && shell != null) {
                int cols = Math.max(1, message.get("cols").getAsInt());
                int rows = Math.max(1, message.get("rows").getAsInt());
                shell.setPtySize(cols, rows, 0, 0);
            }
        } catch (Exception e) {
            send(Map.of("type", "error", "message", safeMessage(e)));
        }
    }

    private void pump(InputStream output) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = output.read(buffer)) >= 0) {
                if (read > 0) send(Map.of("type", "output", "data", new String(buffer, 0, read, StandardCharsets.UTF_8)));
            }
            send(Map.of("type", "status", "message", "closed"));
        } catch (Exception e) {
            if (webSocket != null && webSocket.isOpen()) send(Map.of("type", "error", "message", "Terminal stream closed"));
        } finally {
            closeShell();
        }
    }

    @OnWebSocketClose public void onClose(int code, String reason) { closeShell(); }
    @OnWebSocketError public void onError(Throwable error) { closeShell(); }

    private synchronized void send(Object payload) {
        try {
            if (webSocket != null && webSocket.isOpen()) webSocket.getRemote().sendString(JsonUtil.toCompactJson(payload));
        } catch (Exception ignored) {}
    }

    private void closeShell() { if (shell != null) shell.disconnect(); }

    static String pathId(Session session, String segment) {
        String[] parts = session.getUpgradeRequest().getRequestURI().getPath().split("/");
        for (int i = 0; i < parts.length - 1; i++) if (segment.equals(parts[i])) return parts[i + 1];
        throw new ApiException(400, "INVALID_WEBSOCKET_PATH", "Missing session identifier");
    }

    static String safeMessage(Exception e) {
        return e instanceof ApiException ? e.getMessage() : "SSH terminal operation failed";
    }
}
