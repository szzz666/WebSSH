package top.szzz666.ssh;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import top.szzz666.config.MyConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class SshConnection implements AutoCloseable {
    private final String id;
    private final Session session;
    private final Instant connectedAt = Instant.now();

    SshConnection(String id, Session session) {
        this.id = id;
        this.session = session;
    }

    public String id() { return id; }
    public Session session() { return session; }
    public Instant connectedAt() { return connectedAt; }
    public boolean isConnected() { return session.isConnected(); }

    public ChannelSftp openSftp() throws JSchException {
        Channel channel = session.openChannel("sftp");
        channel.connect(MyConfig.connectTimeoutMs);
        return (ChannelSftp) channel;
    }

    public String exec(String command) throws Exception {
        return exec(command, MyConfig.commandTimeoutMs);
    }

    public String exec(String command, long timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setErrStream(stderr);
        InputStream stdout = channel.getInputStream();
        channel.connect(MyConfig.connectTimeoutMs);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!channel.isClosed()) {
            while (stdout.available() > 0) result.write(buffer, 0, stdout.read(buffer));
            if (System.currentTimeMillis() > deadline) {
                channel.disconnect();
                throw new IllegalStateException("Remote command timed out");
            }
            Thread.sleep(20);
        }
        while (stdout.available() > 0) result.write(buffer, 0, stdout.read(buffer));
        int exit = channel.getExitStatus();
        channel.disconnect();
        if (exit != 0) {
            String error = stderr.toString(StandardCharsets.UTF_8).trim();
            throw new IllegalStateException(error.isEmpty() ? "Remote command failed with exit code " + exit : error);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    @Override public void close() { session.disconnect(); }
}
