package top.szzz666;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.szzz666.config.EasyConfig;
import top.szzz666.config.MyConfig;
import top.szzz666.server.WebServer;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    public static EasyConfig config;

    public static void main(String[] args) {
        MyConfig.loadConfig();
        Runtime.getRuntime().addShutdownHook(new Thread(WebServer::stop, "webssh-shutdown"));
        WebServer.start();
        logger.info("WebSSH listening on http://{}:{}", MyConfig.serverHost, MyConfig.serverPort);
    }
}
