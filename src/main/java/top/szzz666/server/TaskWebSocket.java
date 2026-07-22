package top.szzz666.server;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class TaskWebSocket {
    private TaskManager.Task task;
    @OnWebSocketConnect public void connect(Session session) {
        try {
            task = TaskManager.require(TerminalWebSocket.pathId(session, "tasks"));
            task.subscribe(session);
        } catch (ApiException e) { session.close(1008, e.getMessage()); }
    }
    @OnWebSocketClose public void close(Session session, int code, String reason) { if (task != null) task.unsubscribe(session); }
    @OnWebSocketError public void error(Session session, Throwable error) { if (task != null) task.unsubscribe(session); }
}
