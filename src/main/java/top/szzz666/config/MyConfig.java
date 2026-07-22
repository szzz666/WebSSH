package top.szzz666.config;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.ArrayList;

import static top.szzz666.Main.config;

public class MyConfig {

    //日志
    @ConfigItem(key = "log.level", comment = "日志等级: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL")
    public static String logLevel = "INFO";
    //设置
    @ConfigItem(key = "settings.cordPoolSize", comment = "核心线程数：-1时为自动即 CPU核心数 / 2")
    public static int cordPoolSize = 1;
    @ConfigItem(key = "settings.maxPoolSize", comment = "最大线程数：-1时为自动即 CPU核心数 * 4")
    public static int maxPoolSize = -1;
    @ConfigItem(key = "settings.keepAliveTime", comment = "非核心线程存活时间（秒）")
    public static long keepAliveTime = 60L;
    @ConfigItem(key = "settings.maxQueueSize", comment = "最大队列大小")
    public static int maxQueueSize = 100;
    @ConfigItem(key = "server.host", comment = "Web 服务监听地址")
    public static String serverHost = "127.0.0.1";
    @ConfigItem(key = "server.port", comment = "REST、WebSocket 与静态页面端口")
    public static int serverPort = 8080;
    @ConfigItem(key = "ssh.connectTimeoutMs", comment = "SSH 建连与认证超时（毫秒）")
    public static int connectTimeoutMs = 15000;
    @ConfigItem(key = "ssh.commandTimeoutMs", comment = "监控与任务命令超时（毫秒）")
    public static int commandTimeoutMs = 30000;
    @ConfigItem(key = "ssh.maxUploadBytes", comment = "单文件上传上限（字节）")
    public static long maxUploadBytes = 104857600L;


    public static void loadConfig() {
        config = new EasyConfig("config.yml");
        config.loadFromClass(MyConfig.class);
        config.load();
        applyLogLevel();
    }

    /**
     * 根据配置设置日志等级
     */
    public static void applyLogLevel() {
        try {
            Level level = Level.valueOf(logLevel.toUpperCase());
            Configurator.setRootLevel(level);
        } catch (Exception e) {
            Configurator.setRootLevel(Level.INFO);
        }
    }


}
