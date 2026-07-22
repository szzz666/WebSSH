package top.szzz666.server;

import com.google.gson.JsonSyntaxException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;
import spark.Spark;
import top.szzz666.config.MyConfig;
import top.szzz666.ssh.ConnectionManager;
import top.szzz666.ssh.ConnectionProfile;
import top.szzz666.ssh.SshConnection;
import top.szzz666.tools.JsonUtil;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class WebServer {
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
    private static final int MAX_EDITABLE_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SHORTCUT_FILE_BYTES = 16 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String DESKTOP_DIRECTORY = "/root/.webssh/desktop";
    private static final String SHORTCUT_EXTENSION = ".webssh-link";
    private static Service service;
    private WebServer() {}

    public static void start() {
        service = Service.ignite().ipAddress(MyConfig.serverHost).port(MyConfig.serverPort);
        service.webSocket("/api/v1/sessions/*", TerminalWebSocket.class);
        service.webSocket("/api/v1/tasks/*", TaskWebSocket.class);
        service.staticFiles.location("/public");
        service.staticFiles.expireTime(3600);
        service.before((request, response) -> response.type("application/json; charset=utf-8"));
        service.after((request, response) -> {
            String path = request.pathInfo();
            if (path.startsWith("/api/")) {
                response.header("Cache-Control", "no-store");
            } else if ("/".equals(path) || path.endsWith(".html")) {
                response.header("Cache-Control", "no-cache");
            }
        });
        service.before((request, response) -> {
            String path = request.pathInfo();
            if (!"GET".equalsIgnoreCase(request.requestMethod()) || !path.matches("/api/v1/connections/[^/]+/screen-sessions")) return;
            String connectionId = path.substring("/api/v1/connections/".length(), path.length() - "/screen-sessions".length());
            try {
                response.status(200);
                response.body(json(response, Map.of("sessions", ScreenSessionManager.list(connectionId))));
            } catch (ApiException e) {
                response.status(e.status());
                response.body(error(response, e.status(), e.code(), e.getMessage()));
            } catch (Exception e) {
                logger.warn("Unable to list screen sessions for connection {}", connectionId, e);
                response.status(502);
                response.body(error(response, 502, "SCREEN_LIST_FAILED", e.getMessage()));
            }
            Spark.halt(response.status(), response.body());
        });
        // Handle JSON POST calls before Spark's route matcher. Some embedded
        // Jetty/Spark combinations do not dispatch parameterized POST routes reliably.
        service.before((request, response) -> {
            if (!"POST".equalsIgnoreCase(request.requestMethod())) return;
            String path = request.pathInfo();
            boolean bootstrap = "/api/v1/connections/test".equals(path) || "/api/v1/connections".equals(path);
            boolean desktopShortcuts = "/api/v1/desktop-shortcuts".equals(path);
            boolean fileOperation = path.matches("/api/v1/connections/[^/]+/files/operation");
            boolean runFile = path.matches("/api/v1/connections/[^/]+/screen-sessions");
            boolean powerOperation = path.matches("/api/v1/connections/[^/]+/power");
            if (!bootstrap && !desktopShortcuts && !fileOperation && !runFile && !powerOperation) return;
            try {
                Object result;
                if (desktopShortcuts) {
                    result = DesktopShortcutStore.save(request.body());
                } else if (fileOperation) {
                    String connectionId = path.substring("/api/v1/connections/".length(), path.length() - "/files/operation".length());
                    result = operation(request, connectionId);
                } else if (runFile) {
                    String connectionId = path.substring("/api/v1/connections/".length(), path.length() - "/screen-sessions".length());
                    result = runFile(request, connectionId);
                } else if (powerOperation) {
                    String connectionId = path.substring("/api/v1/connections/".length(), path.length() - "/power".length());
                    Map<String, Object> data = JsonUtil.toMap(request.body());
                    if (!"reboot".equals(string(data.get("action")))) throw new ApiException(400, "VALIDATION_ERROR", "action must be reboot");
                    String taskId = TaskManager.start(connectionId, "shutdown -r now 2>/dev/null || systemctl reboot");
                    result = Map.of("status", "rebooting", "taskId", taskId);
                } else {
                    result = "/api/v1/connections/test".equals(path)
                            ? ConnectionManager.test(body(request, ConnectionProfile.class))
                            : ConnectionManager.connect(body(request, ConnectionProfile.class));
                }
                response.status(200);
                response.body(json(response, result));
            } catch (ApiException e) {
                response.status(e.status());
                response.body(error(response, e.status(), e.code(), e.getMessage()));
            }
            Spark.halt(response.status(), response.body());
        });
        service.options("/*", (request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Allow-Headers", "Content-Type");
            response.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            return "";
        });
        service.post("/api/v1/connections/test", (req, res) -> json(res, ConnectionManager.test(body(req, ConnectionProfile.class))));
        service.post("/api/v1/connections", (req, res) -> json(res, ConnectionManager.connect(body(req, ConnectionProfile.class))));
        service.get("/api/v1/desktop-shortcuts", (req, res) -> json(res, DesktopShortcutStore.load()));
        service.post("/api/v1/desktop-shortcuts", (req, res) -> json(res, DesktopShortcutStore.save(req.body())));
        service.get("/api/v1/connections/:id/status", (req, res) -> {
            SshConnection c = ConnectionManager.require(req.params("id"));
            return json(res, Map.of("id", c.id(), "status", "connected", "connectedAt", c.connectedAt().toString()));
        });
        service.get("/api/v1/connections/:id/time", (req, res) -> {
            SshConnection c = ConnectionManager.require(req.params("id"));
            String output = c.exec("date +%s%3N 2>/dev/null || date +%s000");
            long serverTime = Long.parseLong(output.trim().replaceAll("[^0-9]", ""));
            return json(res, Map.of("serverTime", serverTime));
        });
        service.delete("/api/v1/connections/:id", (req, res) -> { ScreenSessionManager.removeConnection(req.params("id")); ConnectionManager.require(req.params("id")).close(); return json(res, Map.of("status", "disconnected")); });
        service.get("/api/v1/connections/:id/files", WebServer::listFiles);
        service.get("/api/v1/connections/:id/desktop", WebServer::desktop);
        service.get("/api/v1/connections/:id/files/content", WebServer::readTextFile);
        service.post("/api/v1/connections/:id/files/upload", WebServer::upload);
        service.get("/api/v1/connections/:id/files/download", WebServer::download);
        service.post("/api/v1/connections/:id/files/operation", (req, res) -> json(res, operation(req, req.params("id"))));
        service.get("/api/v1/connections/:id/screen-sessions", (req, res) -> json(res, Map.of("sessions", ScreenSessionManager.list(req.params("id")))));
        service.post("/api/v1/connections/:id/screen-sessions", (req, res) -> json(res, runFile(req, req.params("id"))));
        service.post("/api/v1/connections/:id/power", (req, res) -> {
            Map<String, Object> data = JsonUtil.toMap(req.body());
            if (!"reboot".equals(string(data.get("action")))) throw new ApiException(400, "VALIDATION_ERROR", "action must be reboot");
            res.status(202);
            return json(res, Map.of("status", "rebooting", "taskId", TaskManager.start(req.params("id"), "shutdown -r now 2>/dev/null || systemctl reboot")));
        });
        service.get("/api/v1/file-jobs/:id", (req, res) -> json(res, FileJobManager.status(req.params("id"))));
        service.get("/api/v1/connections/:id/metrics", WebServer::metrics);
        service.post("/api/v1/connections/:id/tasks", (req, res) -> {
            Map<String, Object> data = JsonUtil.toMap(req.body());
            String taskId = TaskManager.start(req.params("id"), string(data.get("command")));
            res.status(202);
            return json(res, Map.of("taskId", taskId, "status", "running"));
        });
        service.exception(ApiException.class, (e, req, res) -> error(res, e.status(), e.code(), e.getMessage()));
        service.exception(JsonSyntaxException.class, (e, req, res) -> error(res, 400, "INVALID_JSON", "Request body is not valid JSON"));
        service.exception(Exception.class, (e, req, res) -> {
            logger.error("Unhandled API error at {}", req.pathInfo(), e);
            error(res, 500, "INTERNAL_ERROR", "Internal server error");
        });
        service.notFound((req, res) -> {
            if (!req.pathInfo().startsWith("/api/")) {
                String resource = "/public" + req.pathInfo();
                Object asset = staticResource(res, resource, contentType(req.pathInfo()));
                if (asset != null) return asset;
                if (!req.pathInfo().contains(".")) {
                    return staticResource(res, "/public/index.html", "text/html; charset=utf-8");
                }
            }
            return error(res, 404, "NOT_FOUND", "Resource not found");
        });
        service.init();
        service.awaitInitialization();
    }

    public static void stop() {
        ConnectionManager.closeAll();
        if (service != null) service.stop();
    }

    private static Object listFiles(Request req, Response res) throws Exception {
        String path = queryPath(req);
        ChannelSftp sftp = ConnectionManager.require(req.params("id")).openSftp();
        try {
            Vector<?> files = sftp.ls(path);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object object : files) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
                if (entry.getFilename().equals(".") || entry.getFilename().equals("..")) continue;
                SftpATTRS a = entry.getAttrs();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getFilename());
                item.put("fileName", entry.getFilename());
                item.put("type", a.isDir() ? "directory" : "file");
                item.put("size", a.getSize());
                item.put("permissions", a.getPermissionsString());
                item.put("mode", String.format("%04o", a.getPermissions() & 07777));
                item.put("owner", String.valueOf(a.getUId()));
                item.put("modifiedAt", Instant.ofEpochSecond(a.getMTime()).toString());
                entries.add(item);
            }
            return json(res, Map.of("path", sftp.realpath(path), "entries", entries));
        } finally { sftp.disconnect(); }
    }

    private static Object desktop(Request req, Response res) throws Exception {
        ChannelSftp sftp = ConnectionManager.require(req.params("id")).openSftp();
        try {
            ensureDirectory(sftp, "/root/.webssh");
            ensureDirectory(sftp, DESKTOP_DIRECTORY);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object object : sftp.ls(DESKTOP_DIRECTORY)) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
                if (entry.getFilename().equals(".") || entry.getFilename().equals("..")) continue;
                SftpATTRS attrs = entry.getAttrs();
                String path = join(DESKTOP_DIRECTORY, entry.getFilename());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getFilename());
                item.put("fileName", entry.getFilename());
                item.put("path", path);
                item.put("type", attrs.isDir() ? "directory" : "file");
                item.put("permissions", attrs.getPermissionsString());
                item.put("mode", String.format("%04o", attrs.getPermissions() & 07777));
                item.put("size", attrs.getSize());
                item.put("modifiedAt", Instant.ofEpochSecond(attrs.getMTime()).toString());
                if (!attrs.isDir() && entry.getFilename().endsWith(SHORTCUT_EXTENSION) && attrs.getSize() <= MAX_SHORTCUT_FILE_BYTES) {
                    try (InputStream input = sftp.get(path)) {
                        byte[] content = input.readNBytes(MAX_SHORTCUT_FILE_BYTES + 1);
                        Map<String, Object> shortcut = JsonUtil.toMap(new String(content, StandardCharsets.UTF_8));
                        String target = string(shortcut.get("target"));
                        String targetType = string(shortcut.get("targetType"));
                        if (target != null && ("file".equals(targetType) || "directory".equals(targetType))) {
                            item.put("type", "shortcut");
                            item.put("target", target);
                            item.put("targetType", targetType);
                            item.put("name", stripShortcutExtension(entry.getFilename()));
                        }
                    } catch (RuntimeException ignored) {
                        // Invalid shortcut documents remain visible as ordinary files.
                    }
                }
                entries.add(item);
            }
            return json(res, Map.of("path", DESKTOP_DIRECTORY, "entries", entries));
        } catch (SftpException e) {
            throw new ApiException(e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED ? 403 : 502, "SFTP_ERROR", e.getMessage());
        } finally { sftp.disconnect(); }
    }

    private static Object runFile(Request req, String connectionId) throws Exception {
        Map<String, Object> data = JsonUtil.toMap(req.body());
        String action = string(data.get("action"));
        String name = string(data.get("name"));
        if ("kill".equals(action)) { ScreenSessionManager.kill(connectionId, name); return Map.of("status", "closed", "name", name); }
        String path = string(data.get("path"));
        if (path != null && !path.isBlank()) {
            if (!path.startsWith("/")) throw new ApiException(400, "VALIDATION_ERROR", "path must be absolute");
            ChannelSftp sftp = ConnectionManager.require(connectionId).openSftp();
            try {
                SftpATTRS attrs = sftp.stat(path);
                if (attrs.isDir()) throw new ApiException(400, "NOT_EXECUTABLE", "Directories cannot be run");
                if ((attrs.getPermissions() & 0111) == 0) throw new ApiException(400, "NOT_EXECUTABLE", "File does not have execute permission");
            } catch (SftpException e) {
                throw new ApiException(e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE ? 404 : 502, "SFTP_ERROR", e.getMessage());
            } finally { sftp.disconnect(); }
        }
        try {
            boolean autoExit = !data.containsKey("autoExit") || Boolean.parseBoolean(String.valueOf(data.get("autoExit")));
            return ScreenSessionManager.start(connectionId, path, string(data.get("label")), autoExit);
        } catch (IllegalStateException e) {
            throw new ApiException(422, "SCREEN_START_FAILED", e.getMessage());
        }
    }

    private static Object upload(Request req, Response res) throws Exception {
        req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement(System.getProperty("java.io.tmpdir"), MyConfig.maxUploadBytes, MyConfig.maxUploadBytes, 1024 * 1024));
        String path = req.queryParams("path");
        if (path == null || path.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "path query parameter is required");
        Part part = req.raw().getPart("file");
        if (part == null) throw new ApiException(400, "VALIDATION_ERROR", "multipart field file is required");
        if (part.getSize() > MyConfig.maxUploadBytes) throw new ApiException(413, "FILE_TOO_LARGE", "Upload exceeds configured size limit");
        String target = path.endsWith("/") ? path + safeFilename(part.getSubmittedFileName()) : path;
        ChannelSftp sftp = ConnectionManager.require(req.params("id")).openSftp();
        try (InputStream input = part.getInputStream()) { sftp.put(input, target); }
        finally { sftp.disconnect(); part.delete(); }
        res.status(201);
        return json(res, Map.of("path", target, "size", part.getSize()));
    }

    private static Object download(Request req, Response res) throws Exception {
        String path = queryPath(req);
        ChannelSftp sftp = ConnectionManager.require(req.params("id")).openSftp();
        try {
            SftpATTRS attrs = sftp.stat(path);
            if (attrs.isDir()) {
                String filename = (baseName(path).isBlank() ? "download" : baseName(path)) + ".zip";
                res.type("application/zip");
                res.header("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
                try (ZipOutputStream zip = new ZipOutputStream(res.raw().getOutputStream(), StandardCharsets.UTF_8)) {
                    zipDirectory(sftp, path, baseName(path).isBlank() ? "download" : baseName(path), zip);
                } catch (IOException e) {
                    logger.warn("Download cancelled by client: {}", path);
                    return res.raw();
                }
                return res.raw();
            }
            res.type("application/octet-stream");
            res.header("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(baseName(path), StandardCharsets.UTF_8).replace("+", "%20"));
            res.header("Content-Length", String.valueOf(attrs.getSize()));
            try (InputStream input = sftp.get(path); OutputStream output = res.raw().getOutputStream()) { input.transferTo(output); }
            catch (IOException e) { logger.warn("Download cancelled by client: {}", path); }
            return res.raw();
        } finally { sftp.disconnect(); }
    }

    private static Object readTextFile(Request req, Response res) throws Exception {
        String path = queryPath(req);
        ChannelSftp sftp = ConnectionManager.require(req.params("id")).openSftp();
        try {
            SftpATTRS attrs = sftp.stat(path);
            if (attrs.isDir()) throw new ApiException(400, "NOT_A_FILE", "Directories cannot be edited");
            if (attrs.getSize() > MAX_EDITABLE_FILE_BYTES) throw new ApiException(413, "FILE_TOO_LARGE", "Text editor supports files up to 2 MiB");
            byte[] bytes;
            try (InputStream input = sftp.get(path)) { bytes = input.readNBytes(MAX_EDITABLE_FILE_BYTES + 1); }
            if (bytes.length > MAX_EDITABLE_FILE_BYTES) throw new ApiException(413, "FILE_TOO_LARGE", "Text editor supports files up to 2 MiB");
            if (containsNullByte(bytes)) throw new ApiException(415, "BINARY_FILE", "Binary files cannot be opened in the text editor");
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new ApiException(415, "UNSUPPORTED_ENCODING", "Only UTF-8 text files can be edited");
            }
            return json(res, Map.of("path", path, "content", content, "size", bytes.length, "modifiedAt", Instant.ofEpochSecond(attrs.getMTime()).toString()));
        } finally { sftp.disconnect(); }
    }

    private static Object operation(Request req, String connectionId) throws Exception {
        Map<String, Object> data = JsonUtil.toMap(req.body());
        String action = string(data.get("action"));
        String path = string(data.get("path"));
        if (action == null || path == null) throw new ApiException(400, "VALIDATION_ERROR", "action and path are required");
        if ("compress".equals(action)) return FileJobManager.startCompress(connectionId, paths(data, path), required(data, "destination"));
        if ("extract".equals(action)) return FileJobManager.startExtract(connectionId, path, required(data, "destination"));
        ChannelSftp sftp = ConnectionManager.require(connectionId).openSftp();
        try {
            switch (action) {
                case "mkdir" -> sftp.mkdir(path);
                case "create", "write" -> {
                    byte[] content = string(data.get("content")) == null ? new byte[0] : string(data.get("content")).getBytes(StandardCharsets.UTF_8);
                    if (content.length > MAX_EDITABLE_FILE_BYTES) throw new ApiException(413, "FILE_TOO_LARGE", "Text editor supports files up to 2 MiB");
                    try (InputStream input = new java.io.ByteArrayInputStream(content)) { sftp.put(input, path); }
                }
                case "rename", "move" -> sftp.rename(path, required(data, "destination"));
                case "delete" -> delete(sftp, path);
                case "copy" -> copy(sftp, path, required(data, "destination"));
                case "chmod" -> sftp.chmod(parseMode(required(data, "mode")), path);
                case "desktop-shortcut" -> createDesktopShortcut(sftp, path, required(data, "type"), string(data.get("name")));
                default -> throw new ApiException(400, "UNSUPPORTED_OPERATION", "Unsupported file action: " + action);
            }
            return Map.of("ok", true, "action", action, "path", path);
        } catch (SftpException e) {
            throw new ApiException(e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED ? 403 : 502, "SFTP_ERROR", e.getMessage());
        } finally { sftp.disconnect(); }
    }

    private static Object metrics(Request req, Response res) throws Exception {
        String command = "LC_ALL=C; export LC_ALL; " +
                "cpu1=$(awk '/^cpu /{idle=$5+$6; total=$2+$3+$4+idle+$7+$8+$9; print idle,total}' /proc/stat); " +
                "sleep 1; cpu2=$(awk '/^cpu /{idle=$5+$6; total=$2+$3+$4+idle+$7+$8+$9; print idle,total}' /proc/stat); " +
                "set -- $cpu1; idle1=$1; total1=$2; set -- $cpu2; idle2=$1; total2=$2; " +
                "awk -v i1=$idle1 -v t1=$total1 -v i2=$idle2 -v t2=$total2 'BEGIN{dt=t2-t1; printf \"cpu=%.1f\\n\",dt ? 100*(1-(i2-i1)/dt) : 0}'; " +
                "free -b | awk '/^Mem:/{printf \"memory=%.1f\\nmemoryTotal=%.0f\\nmemoryUsed=%.0f\\nmemoryAvailable=%.0f\\n\",$2?100*$3/$2:0,$2,$3,$7} /^Swap:/{printf \"swap=%.1f\\nswapTotal=%.0f\\nswapUsed=%.0f\\n\",$2?100*$3/$2:0,$2,$3}'; " +
                "df -PB1 / | awk 'NR==2{gsub(/%/,\"\",$5); print \"disk=\"$5\"\\ndiskTotal=\"$2\"\\ndiskUsed=\"$3\"\\ndiskAvailable=\"$4}'; " +
                "awk '{print \"load=\"$1\" \"$2\" \"$3}' /proc/loadavg; " +
                "awk 'NR>2{rx+=$2;tx+=$10} END{print \"download=\"rx\"\\nupload=\"tx}' /proc/net/dev; " +
                "awk '{print \"uptime=\"int($1)}' /proc/uptime; " +
                "awk -F: '/^model name/{gsub(/^[ \\t]+/,\"\",$2); print \"cpuModel=\"$2; exit}' /proc/cpuinfo; " +
                "awk '/^processor/{n++} END{print \"cpuCores=\"n}' /proc/cpuinfo; " +
                "printf 'hostname='; hostname; printf 'kernel='; uname -sr; " +
                "printf 'processes='; ps -e 2>/dev/null | awk 'NR>1{n++} END{print n+0}'";
        String output = ConnectionManager.require(req.params("id")).exec(command);
        Map<String, Object> metric = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) metric.put(line.substring(0, equals), numberOrText(line.substring(equals + 1)));
        }
        metric.put("collectedAt", Instant.now().toString());
        return json(res, metric);
    }

    private static void delete(ChannelSftp sftp, String path) throws SftpException {
        if (!sftp.stat(path).isDir()) { sftp.rm(path); return; }
        Vector<?> children = sftp.ls(path);
        for (Object object : children) {
            ChannelSftp.LsEntry child = (ChannelSftp.LsEntry) object;
            if (!child.getFilename().equals(".") && !child.getFilename().equals("..")) delete(sftp, join(path, child.getFilename()));
        }
        sftp.rmdir(path);
    }

    private static void copy(ChannelSftp sftp, String source, String destination) throws Exception {
        if (!sftp.stat(source).isDir()) {
            try (InputStream input = sftp.get(source)) { sftp.put(input, destination); }
            return;
        }
        sftp.mkdir(destination);
        for (Object object : sftp.ls(source)) {
            ChannelSftp.LsEntry child = (ChannelSftp.LsEntry) object;
            if (!child.getFilename().equals(".") && !child.getFilename().equals("..")) copy(sftp, join(source, child.getFilename()), join(destination, child.getFilename()));
        }
    }

    private static void zipDirectory(ChannelSftp sftp, String path, String entryPath, ZipOutputStream zip) throws Exception {
        zip.putNextEntry(new ZipEntry(entryPath + "/"));
        zip.closeEntry();
        for (Object object : sftp.ls(path)) {
            ChannelSftp.LsEntry child = (ChannelSftp.LsEntry) object;
            if (child.getFilename().equals(".") || child.getFilename().equals("..")) continue;
            String childPath = join(path, child.getFilename());
            String childEntry = entryPath + "/" + child.getFilename();
            if (child.getAttrs().isDir()) {
                zipDirectory(sftp, childPath, childEntry, zip);
            } else {
                zip.putNextEntry(new ZipEntry(childEntry));
                try (InputStream input = sftp.get(childPath)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
    }

    private static void compress(ChannelSftp sourceSftp, ChannelSftp targetSftp, List<String> sources, String destination) throws Exception {
        compress(sourceSftp, targetSftp, sources, destination, ignored -> {});
    }

    private static void compress(ChannelSftp sourceSftp, ChannelSftp targetSftp, List<String> sources, String destination, LongConsumer progress) throws Exception {
        if (!destination.toLowerCase().endsWith(".zip")) throw new ApiException(400, "VALIDATION_ERROR", "destination must end with .zip");
        if (sources.contains(destination)) throw new ApiException(409, "INVALID_DESTINATION", "Archive destination cannot overwrite a source file");
        try (ZipOutputStream zip = new ZipOutputStream(targetSftp.put(destination), StandardCharsets.UTF_8)) {
            for (String source : sources) {
                String name = baseName(source);
                if (name.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Cannot compress a filesystem root");
                if (sourceSftp.stat(source).isDir()) zipDirectory(sourceSftp, source, name, zip, progress);
                else {
                    zip.putNextEntry(new ZipEntry(name));
                    copyWithProgress(sourceSftp.get(source), zip, progress);
                    zip.closeEntry();
                }
            }
        } catch (Exception | Error failure) {
            try { sourceSftp.rm(destination); }
            catch (SftpException cleanupError) { logger.warn("Could not remove incomplete archive {}", destination, cleanupError); }
            throw failure;
        }
    }

    private static void extract(ChannelSftp sourceSftp, ChannelSftp targetSftp, String archive, String destination) throws Exception {
        extract(sourceSftp, targetSftp, archive, destination, ignored -> {});
    }

    private static void extract(ChannelSftp sourceSftp, ChannelSftp targetSftp, String archive, String destination, LongConsumer progress) throws Exception {
        ensureDirectoryTree(targetSftp, destination);
        int entries = 0;
        long extracted = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ProgressInputStream(sourceSftp.get(archive), progress), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new ApiException(413, "ARCHIVE_TOO_LARGE", "Archive contains too many entries");
                String relative = safeZipEntry(entry.getName());
                if (relative.isBlank()) { zip.closeEntry(); continue; }
                String target = join(destination, relative);
                if (entry.isDirectory()) {
                    ensureDirectoryTree(targetSftp, target);
                } else {
                    int slash = target.lastIndexOf('/');
                    if (slash > 0) ensureDirectoryTree(targetSftp, target.substring(0, slash));
                    try (OutputStream output = targetSftp.put(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            extracted += count;
                            if (extracted > MAX_EXTRACTED_BYTES) throw new ApiException(413, "ARCHIVE_TOO_LARGE", "Extracted content exceeds 2 GiB");
                            output.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void ensureDirectoryTree(ChannelSftp sftp, String path) throws SftpException {
        if (path == null || path.isBlank() || "/".equals(path)) return;
        String current = path.startsWith("/") ? "/" : "";
        for (String part : path.split("/")) {
            if (part.isBlank()) continue;
            current = current.isEmpty() || current.equals("/") ? current + part : current + "/" + part;
            ensureDirectory(sftp, current);
        }
    }

    private static String safeZipEntry(String name) {
        if (name == null) throw new ApiException(400, "INVALID_ARCHIVE", "Archive entry has no name");
        String value = name.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw new ApiException(400, "INVALID_ARCHIVE", "Archive contains an absolute path");
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isBlank() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0) throw new ApiException(400, "INVALID_ARCHIVE", "Archive contains an unsafe path");
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private static List<String> paths(Map<String, Object> data, String fallback) {
        Object value = data.get("paths");
        if (!(value instanceof List<?> values)) return List.of(fallback);
        List<String> result = values.stream().map(WebServer::string).filter(item -> item != null && !item.isBlank()).toList();
        if (result.isEmpty() || result.size() > 200) throw new ApiException(400, "VALIDATION_ERROR", "paths must contain between 1 and 200 entries");
        return result;
    }

    private static void zipDirectory(ChannelSftp sftp, String path, String entryPath, ZipOutputStream zip, LongConsumer progress) throws Exception {
        zip.putNextEntry(new ZipEntry(entryPath + "/"));
        zip.closeEntry();
        for (Object object : sftp.ls(path)) {
            ChannelSftp.LsEntry child = (ChannelSftp.LsEntry) object;
            if (child.getFilename().equals(".") || child.getFilename().equals("..")) continue;
            String childPath = join(path, child.getFilename());
            String childEntry = entryPath + "/" + child.getFilename();
            if (child.getAttrs().isDir()) zipDirectory(sftp, childPath, childEntry, zip, progress);
            else {
                zip.putNextEntry(new ZipEntry(childEntry));
                copyWithProgress(sftp.get(childPath), zip, progress);
                zip.closeEntry();
            }
        }
    }

    private static void copyWithProgress(InputStream source, OutputStream destination, LongConsumer progress) throws Exception {
        try (InputStream input = source) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
                progress.accept(count);
            }
        }
    }

    private static long sourceSize(ChannelSftp sftp, String path) throws SftpException {
        SftpATTRS attrs = sftp.stat(path);
        if (!attrs.isDir()) return attrs.getSize();
        long total = 0;
        for (Object object : sftp.ls(path)) {
            ChannelSftp.LsEntry child = (ChannelSftp.LsEntry) object;
            if (!child.getFilename().equals(".") && !child.getFilename().equals("..")) total += sourceSize(sftp, join(path, child.getFilename()));
        }
        return total;
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final LongConsumer progress;
        ProgressInputStream(InputStream input, LongConsumer progress) { super(input); this.progress = progress; }
        @Override public int read() throws java.io.IOException { int value = super.read(); if (value >= 0) progress.accept(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { int count = super.read(bytes, offset, length); if (count > 0) progress.accept(count); return count; }
    }

    private static final class FileJobManager {
        private static final Map<String, FileJob> jobs = new ConcurrentHashMap<>();
        private static Map<String, Object> startCompress(String connectionId, List<String> sources, String destination) {
            return start("compress", connectionId, job -> {
                try (SftpHandle source = new SftpHandle(connectionId); SftpHandle target = new SftpHandle(connectionId)) {
                    long total = 0;
                    for (String path : sources) total += sourceSize(source.sftp, path);
                    job.total = Math.max(1, total);
                    compress(source.sftp, target.sftp, sources, destination, job::advance);
                }
            });
        }
        private static Map<String, Object> startExtract(String connectionId, String archive, String destination) {
            return start("extract", connectionId, job -> {
                try (SftpHandle source = new SftpHandle(connectionId); SftpHandle target = new SftpHandle(connectionId)) {
                    job.total = Math.max(1, source.sftp.stat(archive).getSize());
                    extract(source.sftp, target.sftp, archive, destination, job::advance);
                }
            });
        }
        private static Map<String, Object> start(String type, String connectionId, JobAction action) {
            ConnectionManager.require(connectionId);
            String id = UUID.randomUUID().toString();
            FileJob job = new FileJob(id, type);
            jobs.put(id, job);
            Thread.ofVirtual().name("file-job-" + id).start(() -> {
                job.status = "running";
                try { action.run(job); job.processed = job.total; job.status = "completed"; }
                catch (Exception e) { logger.warn("File job {} failed", id, e); job.error = e.getMessage() == null ? "File operation failed" : e.getMessage(); job.status = "failed"; }
                job.finishedAt = System.currentTimeMillis();
            });
            return Map.of("jobId", id, "status", "queued", "type", type);
        }
        private static Map<String, Object> status(String id) {
            FileJob job = jobs.get(id);
            if (job == null) throw new ApiException(404, "FILE_JOB_NOT_FOUND", "File job does not exist");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", job.id); result.put("type", job.type); result.put("status", job.status);
            result.put("processed", job.processed); result.put("total", job.total);
            result.put("percent", job.total <= 0 ? 0 : Math.min(100, Math.round(job.processed * 100.0 / job.total)));
            if (job.error != null) result.put("error", job.error);
            if (job.finishedAt > 0 && System.currentTimeMillis() - job.finishedAt > 10 * 60_000L) jobs.remove(id);
            return result;
        }
        @FunctionalInterface private interface JobAction { void run(FileJob job) throws Exception; }
        private static final class FileJob {
            final String id; final String type; volatile String status = "queued"; volatile long processed; volatile long total; volatile String error; volatile long finishedAt;
            FileJob(String id, String type) { this.id = id; this.type = type; }
            void advance(long bytes) { processed += bytes; }
        }
        private static final class SftpHandle implements AutoCloseable {
            final ChannelSftp sftp;
            SftpHandle(String connectionId) throws Exception { sftp = ConnectionManager.require(connectionId).openSftp(); }
            @Override public void close() { sftp.disconnect(); }
        }
    }

    private static void createDesktopShortcut(ChannelSftp sftp, String target, String targetType, String displayName) throws Exception {
        if (!"file".equals(targetType) && !"directory".equals(targetType)) throw new ApiException(400, "VALIDATION_ERROR", "type must be file or directory");
        ensureDirectory(sftp, "/root/.webssh");
        ensureDirectory(sftp, DESKTOP_DIRECTORY);
        String name = displayName == null || displayName.isBlank() ? baseName(target) : displayName.trim();
        String filename = safeDesktopName(name) + SHORTCUT_EXTENSION;
        Map<String, Object> shortcut = new LinkedHashMap<>();
        shortcut.put("version", 1);
        shortcut.put("kind", "remote-shortcut");
        shortcut.put("name", name);
        shortcut.put("target", target);
        shortcut.put("targetType", targetType);
        byte[] content = JsonUtil.toCompactJson(shortcut).getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new java.io.ByteArrayInputStream(content)) { sftp.put(input, join(DESKTOP_DIRECTORY, filename)); }
        sftp.chmod(0600, join(DESKTOP_DIRECTORY, filename));
    }

    private static void ensureDirectory(ChannelSftp sftp, String path) throws SftpException {
        try {
            if (!sftp.stat(path).isDir()) throw new ApiException(409, "NOT_A_DIRECTORY", path + " is not a directory");
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
            sftp.mkdir(path);
            sftp.chmod(0700, path);
        }
    }

    private static int parseMode(String mode) {
        if (!mode.matches("[0-7]{3,4}")) throw new ApiException(400, "VALIDATION_ERROR", "mode must contain 3 or 4 octal digits");
        return Integer.parseInt(mode, 8);
    }

    private static String safeDesktopName(String name) {
        String value = name.replaceAll("[\\x00-\\x1f/\\\\]", "_").trim();
        if (value.equals(".") || value.equals("..") || value.isBlank()) value = "shortcut";
        if (value.length() > 200) value = value.substring(0, 200);
        return value;
    }

    private static String stripShortcutExtension(String name) { return name.substring(0, name.length() - SHORTCUT_EXTENSION.length()); }

    private static String join(String parent, String child) { return (parent.endsWith("/") ? parent : parent + "/") + child; }
    private static String queryPath(Request req) { String path = req.queryParams("path"); if (path == null || path.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "path query parameter is required"); return URLDecoder.decode(path, StandardCharsets.UTF_8); }
    private static String required(Map<String, Object> data, String key) { String value = string(data.get(key)); if (value == null || value.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", key + " is required"); return value; }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static Object numberOrText(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return value; } }
    private static String safeFilename(String filename) { String value = filename == null ? "upload.bin" : filename.replace('\\', '/'); return baseName(value); }
    private static String baseName(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? path : path.substring(slash + 1); }
    private static boolean containsNullByte(byte[] bytes) { for (byte value : bytes) if (value == 0) return true; return false; }
    private static <T> T body(Request req, Class<T> type) { try { return JsonUtil.fromJson(req.body(), type); } catch (RuntimeException e) { throw new ApiException(400, "INVALID_JSON", "Request body is not valid JSON"); } }
    private static String json(Response res, Object value) { res.type("application/json; charset=utf-8"); return JsonUtil.toCompactJson(value); }
    private static String error(Response res, int status, String code, String message) { res.status(status); return json(res, Map.of("error", Map.of("status", status, "code", code, "message", message))); }
    private static String contentType(String path) {
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/html; charset=utf-8";
    }
    private static String staticResource(Response res, String resource, String type) {
        res.type(type);
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }
}
