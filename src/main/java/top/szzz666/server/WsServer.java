package top.szzz666.server;

import com.google.gson.JsonObject;
import com.jcraft.jsch.ChannelShell;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.szzz666.config.MyConfig;
import top.szzz666.ssh.ConnectionManager;
import top.szzz666.tools.JsonUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WsServer {
    private static final Logger logger = LoggerFactory.getLogger(WsServer.class);
    private static final Map<WebSocket, TerminalSession> shells = new ConcurrentHashMap<>();
    private static final Map<WebSocket, TaskManager.Task> taskStreams = new ConcurrentHashMap<>();
    private static WebSocketServer server;
    private WsServer() {}
    public static void start() {
        server = new WebSocketServer(new InetSocketAddress(MyConfig.serverHost, MyConfig.wsPort)) {
            @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
                String path = handshake.getResourceDescriptor();
                try {
                    if (path.matches("/api/v1/sessions/[^/]+/(terminal|screen/[^/?]+)")) {
                        String[] parts = path.split("/");
                        String id = parts[4];
                        ChannelShell shell = (ChannelShell) ConnectionManager.require(id).session().openChannel("shell");
                        shell.setPtyType("xterm-256color");
                        shell.setPtySize(120, 30, 0, 0);
                        TerminalSession terminal = new TerminalSession(shell, shell.getInputStream(), shell.getOutputStream());
                        shell.connect(MyConfig.connectTimeoutMs);
                        if (parts.length == 7 && "screen".equals(parts[5])) terminal.write(ScreenSessionManager.attachCommand(id, parts[6]));
                        shells.put(conn, terminal);
                        conn.send(json(Map.of("type", "status", "message", "connected")));
                        Thread.ofVirtual().name("webssh-terminal-" + id).start(() -> pump(conn, terminal));
                    } else if (path.matches("/api/v1/tasks/[^/]+/stream")) {
                        String id = path.split("/")[4];
                        TaskManager.Task task = TaskManager.require(id); task.subscribe(conn); taskStreams.put(conn, task);
                    }
                    else conn.close(1008, "Unsupported WebSocket path");
                } catch (ApiException e) {
                    logger.info("Rejected WebSocket resource {}: {}", path, e.getMessage());
                    conn.close(4004, e.code());
                } catch (Exception e) {
                    logger.warn("Unable to open WebSocket resource {}", path, e);
                    conn.close(1011, "Unable to open SSH terminal");
                }
            }
            @Override public void onMessage(WebSocket conn, String text) {
                try {
                    JsonObject message = JsonUtil.fromJson(text, JsonObject.class);
                    TerminalSession terminal = shells.get(conn);
                    if (terminal == null || !terminal.shell().isConnected()) throw new IllegalStateException("SSH terminal is not connected");
                    String type = message.has("type") ? message.get("type").getAsString() : "";
                    if ("input".equals(type)) terminal.write(message.has("data") ? message.get("data").getAsString() : "");
                    else if ("resize".equals(type)) terminal.shell().setPtySize(Math.max(1, message.get("cols").getAsInt()), Math.max(1, message.get("rows").getAsInt()), 0, 0);
                    else throw new IllegalArgumentException("Unsupported terminal message type");
                } catch (Exception e) {
                    logger.warn("Unable to process terminal WebSocket message", e);
                    if (conn.isOpen()) conn.send(json(Map.of("type", "error", "message", "Unable to send input to SSH terminal")));
                }
            }
            @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { TerminalSession terminal = shells.remove(conn); if (terminal != null) terminal.close(); TaskManager.Task task = taskStreams.remove(conn); if (task != null) task.unsubscribe(conn); }
            @Override public void onError(WebSocket conn, Exception ex) { logger.warn("WebSocket error", ex); }
            @Override public void onStart() {}
        };
        server.start();
    }
    public static void stop() { if (server != null) try { server.stop(); } catch (Exception ignored) {} }
    private static void pump(WebSocket conn, TerminalSession terminal) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = terminal.output().read(buffer)) >= 0) {
                if (read > 0 && conn.isOpen()) conn.send(json(Map.of("type", "output", "data", new String(buffer, 0, read, StandardCharsets.UTF_8))));
            }
            if (conn.isOpen()) conn.send(json(Map.of("type", "status", "message", "closed")));
        } catch (Exception e) {
            if (conn.isOpen()) conn.send(json(Map.of("type", "error", "message", "SSH terminal output stream closed")));
            logger.debug("SSH terminal output stream closed", e);
        } finally {
            shells.remove(conn, terminal);
            terminal.close();
        }
    }
    private static String json(Object value) { return JsonUtil.toCompactJson(value); }

    private record TerminalSession(ChannelShell shell, InputStream output, OutputStream input) {
        private synchronized void write(String data) throws Exception {
            input.write(data.getBytes(StandardCharsets.UTF_8));
            input.flush();
        }
        private void close() { shell.disconnect(); }
    }
}
