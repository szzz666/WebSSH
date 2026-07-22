package top.szzz666.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.szzz666.config.MyConfig;
import top.szzz666.server.ApiException;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);
    private static final Map<String, SshConnection> connections = new ConcurrentHashMap<>();

    private ConnectionManager() {}

    public static Map<String, Object> test(ConnectionProfile profile) {
        validate(profile);
        Session session = null;
        try {
            session = createSession(profile, true);
            session.connect(MyConfig.connectTimeoutMs);
            return Map.of("ok", true, "message", "SSH authentication succeeded", "fingerprint", fingerprint(session));
        } catch (JSchException e) {
            throw sshError(e);
        } finally {
            if (session != null) session.disconnect();
        }
    }

    public static Map<String, Object> connect(ConnectionProfile profile) {
        validate(profile);
        if (!profile.trustFingerprint) {
            Map<String, Object> tested = test(profile);
            throw new ApiException(409, "FINGERPRINT_REQUIRED", "Host fingerprint confirmation is required: " + ((Map<?, ?>) tested.get("fingerprint")).get("value"));
        }
        try {
            Session session = createSession(profile, true);
            session.connect(MyConfig.connectTimeoutMs);
            String id = UUID.randomUUID().toString();
            connections.put(id, new SshConnection(id, session));
            logger.info("SSH connection {} established to {}:{} as {}", id, profile.host, profile.port, profile.username);
            return Map.of("id", id, "sessionId", id, "fingerprint", fingerprint(session));
        } catch (JSchException e) {
            throw sshError(e);
        }
    }

    private static Session createSession(ConnectionProfile profile, boolean acceptHostKey) throws JSchException {
        JSch jsch = new JSch();
        if ("key".equals(profile.authMethod)) {
            byte[] key = profile.privateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] passphrase = blank(profile.passphrase) ? null : profile.passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jsch.addIdentity("webssh-memory-key", key, null, passphrase);
        }
        if (acceptHostKey) jsch.setHostKeyRepository(new AcceptingHostKeyRepository());
        Session session = jsch.getSession(profile.username, profile.host, profile.port);
        session.setConfig("PreferredAuthentications", "key".equals(profile.authMethod) ? "publickey" : "password,keyboard-interactive");
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setPassword("password".equals(profile.authMethod) ? profile.password : null);
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(3);
        return session;
    }

    private static Map<String, Object> fingerprint(Session session) {
        try {
            HostKey key = session.getHostKey();
            String value = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getKey().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            // JSch exposes the encoded key as text, so hash its decoded wire representation.
            value = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key.getKey())));
            return Map.of("host", session.getHost(), "port", session.getPort(), "algorithm", key.getType(), "value", value, "changed", false);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate host fingerprint", e);
        }
    }

    private static ApiException sshError(JSchException e) {
        String message = e.getMessage() == null ? "SSH connection failed" : e.getMessage();
        if (message.toLowerCase().contains("auth fail")) return new ApiException(401, "AUTHENTICATION_FAILED", "SSH authentication failed");
        return new ApiException(502, "SSH_CONNECTION_FAILED", message);
    }

    private static void validate(ConnectionProfile p) {
        if (p == null || blank(p.host) || blank(p.username)) throw new ApiException(400, "VALIDATION_ERROR", "host and username are required");
        if (p.port < 1 || p.port > 65535) throw new ApiException(400, "VALIDATION_ERROR", "port must be between 1 and 65535");
        if (!"password".equals(p.authMethod) && !"key".equals(p.authMethod)) throw new ApiException(400, "VALIDATION_ERROR", "authMethod must be password or key");
        if ("password".equals(p.authMethod) && blank(p.password)) throw new ApiException(400, "VALIDATION_ERROR", "password is required");
        if ("key".equals(p.authMethod) && blank(p.privateKey)) throw new ApiException(400, "VALIDATION_ERROR", "privateKey is required");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public static SshConnection require(String id) {
        SshConnection connection = connections.get(id);
        if (connection == null) throw new ApiException(404, "CONNECTION_NOT_FOUND", "Connection does not exist or has expired");
        if (!connection.isConnected()) {
            connections.remove(id);
            throw new ApiException(410, "CONNECTION_CLOSED", "SSH connection is closed");
        }
        return connection;
    }

    public static void closeAll() { connections.values().forEach(SshConnection::close); connections.clear(); }

    private static final class AcceptingHostKeyRepository implements HostKeyRepository {
        public int check(String host, byte[] key) { return OK; }
        public void add(HostKey hostkey, com.jcraft.jsch.UserInfo ui) {}
        public void remove(String host, String type) {}
        public void remove(String host, String type, byte[] key) {}
        public String getKnownHostsRepositoryID() { return "webssh-ephemeral-trust"; }
        public HostKey[] getHostKey() { return new HostKey[0]; }
        public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }
}
