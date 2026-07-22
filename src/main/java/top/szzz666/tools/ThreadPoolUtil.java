package top.szzz666.tools;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static top.szzz666.config.MyConfig.*;

public class ThreadPoolUtil {
    //CPU 核心数
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    //核心线程数
    private static final int CORE_POOL_SIZE = cordPoolSize > 0 ? cordPoolSize : (CPU_COUNT / 2);
    //最大线程数
    private static final int MAX_POOL_SIZE = maxPoolSize > 0 ? maxPoolSize : (CPU_COUNT * 4);
    //非核心线程空闲存活时间
    private static final long KEEP_ALIVE_TIME = keepAliveTime;

    //自定义线程工厂
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger threadNum = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "WebSSHAsyncThread#" + threadNum.getAndIncrement());
            // 设置为非守护线程，防止主线程退出后任务被强制中断
            thread.setDaemon(false);
            return thread;
        }
    };

    //有界阻塞队列
    private static final BlockingQueue<Runnable> WORK_QUEUE = new ArrayBlockingQueue<>(maxQueueSize);

    //拒绝策略
    private static final RejectedExecutionHandler HANDLER = new ThreadPoolExecutor.CallerRunsPolicy();

    //线程池实例（使用 volatile 保证多线程下的可见性）
    private static volatile ThreadPoolExecutor threadPool;

    //双重校验锁获取线程池单例
    public static ThreadPoolExecutor getThreadPool() {
        if (threadPool == null) {
            synchronized (ThreadPoolUtil.class) {
                if (threadPool == null) {
                    threadPool = new ThreadPoolExecutor(
                            CORE_POOL_SIZE,
                            MAX_POOL_SIZE,
                            KEEP_ALIVE_TIME,
                            TimeUnit.SECONDS,
                            WORK_QUEUE,
                            THREAD_FACTORY,
                            HANDLER
                    );
                }
            }
        }
        return threadPool;
    }
}
