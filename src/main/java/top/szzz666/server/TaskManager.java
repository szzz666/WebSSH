package top.szzz666.server;

import com.jcraft.jsch.ChannelExec;
import org.eclipse.jetty.websocket.api.Session;
import top.szzz666.config.MyConfig;
import top.szzz666.ssh.ConnectionManager;
import top.szzz666.tools.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskManager {
    private static final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private TaskManager() {}

    public static String start(String connectionId, String command) {
        if (command == null || command.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "command is required");
        String id = UUID.randomUUID().toString();
        Task task = new Task(id);
        tasks.put(id, task);
        Thread.ofVirtual().name("ssh-task-" + id).start(() -> run(task, connectionId, command));
        return id;
    }

    public static Task require(String id) {
        Task task = tasks.get(id);
        if (task == null) throw new ApiException(404, "TASK_NOT_FOUND", "Task does not exist");
        return task;
    }

    private static void run(Task task, String connectionId, String command) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) ConnectionManager.require(connectionId).session().openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(channel.getOutputStream());
            InputStream output = channel.getInputStream();
            channel.connect(MyConfig.connectTimeoutMs);
            byte[] bytes = new byte[8192];
            int read;
            while ((read = output.read(bytes)) >= 0) if (read > 0) task.publish("output", new String(bytes, 0, read, StandardCharsets.UTF_8));
            task.publish("status", "completed:" + channel.getExitStatus());
        } catch (Exception e) {
            task.publish("error", "Remote task failed");
        } finally {
            if (channel != null) channel.disconnect();
            task.done = true;
        }
    }

    public static final class Task {
        private final String id;
        private final StringBuilder history = new StringBuilder();
        private volatile Session subscriber;
        private volatile org.java_websocket.WebSocket javaSubscriber;
        private volatile boolean done;
        Task(String id) { this.id = id; }
        public synchronized void subscribe(Session session) {
            subscriber = session;
            if (!history.isEmpty()) send("output", history.toString());
            if (done) send("status", "completed");
        }
        public synchronized void unsubscribe(Session session) { if (subscriber == session) subscriber = null; }
        public synchronized void subscribe(org.java_websocket.WebSocket socket) {
            javaSubscriber = socket;
            if (!history.isEmpty() && socket.isOpen()) socket.send(JsonUtil.toCompactJson(Map.of("type", "output", "data", history.toString(), "taskId", id)));
            if (done && socket.isOpen()) socket.send(JsonUtil.toCompactJson(Map.of("type", "status", "data", "completed", "taskId", id)));
        }
        public synchronized void unsubscribe(org.java_websocket.WebSocket socket) { if (javaSubscriber == socket) javaSubscriber = null; }
        synchronized void publish(String type, String data) {
            if ("output".equals(type)) history.append(data);
            send(type, data);
        }
        private void send(String type, String data) {
            try {
                if (subscriber != null && subscriber.isOpen()) subscriber.getRemote().sendString(JsonUtil.toCompactJson(Map.of("type", type, "data", data, "taskId", id)));
                if (javaSubscriber != null && javaSubscriber.isOpen()) javaSubscriber.send(JsonUtil.toCompactJson(Map.of("type", type, "data", data, "taskId", id)));
            } catch (Exception ignored) {}
        }
    }
}
