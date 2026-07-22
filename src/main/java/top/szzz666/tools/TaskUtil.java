package top.szzz666.tools;

import cn.hutool.cron.CronUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskUtil {
    // Async(() -> {});
    public static void Async(Runnable logic) {
        ThreadPoolUtil.getThreadPool().execute(logic);
    }

    // Delayed(() -> {}, 1000, TimeUnit.SECONDS);
    public static void Delayed(Runnable logic, int delay, TimeUnit timeUnit) {
        SCHEDULER.schedule(logic, delay, timeUnit);
    }

    // Repeating(() -> {}, 1000, TimeUnit.SECONDS);
    public static void Repeating(Runnable logic, int delay, TimeUnit timeUnit) {
        SCHEDULER.scheduleAtFixedRate(logic, 0, delay, timeUnit);
    }

    // Delayed(() -> {}, 1000, true, TimeUnit.SECONDS);
    public static void Delayed(Runnable logic, int delay, boolean asynchronous, TimeUnit timeUnit) {
        if (asynchronous) {
            SCHEDULER.schedule(() -> ThreadPoolUtil.getThreadPool().execute(logic), delay, timeUnit);
        } else {
            SCHEDULER.schedule(logic, delay, timeUnit);
        }
    }

    // Repeating(() -> {}, 1000, true, TimeUnit.SECONDS);
    public static void Repeating(Runnable logic, int delay, boolean asynchronous, TimeUnit timeUnit) {
        if (asynchronous) {
            SCHEDULER.scheduleAtFixedRate(() -> ThreadPoolUtil.getThreadPool().execute(logic), 0, delay, timeUnit);
        } else {
            SCHEDULER.scheduleAtFixedRate(logic, 0, delay, timeUnit);
        }
    }

    // Delayed(() -> {}, 1000, true) 默认毫秒;
    public static void Delayed(Runnable logic, int delay, boolean asynchronous) {
        Delayed(logic, delay, asynchronous, TimeUnit.MILLISECONDS);
    }

    // Repeating(() -> {}, 1000, true) 默认毫秒;
    public static void Repeating(Runnable logic, int delay, boolean asynchronous) {
        Repeating(logic, delay, asynchronous, TimeUnit.MILLISECONDS);
    }

    // Delayed(() -> {}, 1000) 默认毫秒 默认异步;
    public static void Delayed(Runnable logic, int delay) {
        Delayed(logic, delay, true, TimeUnit.MILLISECONDS);
    }

    // Repeating(() -> {}, 1000) 默认毫秒 默认异步;
    public static void Repeating(Runnable logic, int delay) {
        Repeating(logic, delay, true, TimeUnit.MILLISECONDS);
    }

    // Cron(() -> {}, "0 0 0 * * ?")
    public static String Cron(Runnable logic, String cron) {
        return Cron(logic, cron, true);
    }

    // Cron(() -> {}, "0 0 0 * * ?", true)
    public static String Cron(Runnable logic, String cron, boolean asynchronous) {
        if (asynchronous) {
            return CronUtil.schedule(cron, (Runnable) () -> ThreadPoolUtil.getThreadPool().execute(logic));
        } else {
            return CronUtil.schedule(cron, logic);
        }
    }

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TaskThread");
        thread.setDaemon(false);
        return thread;
    });
}
