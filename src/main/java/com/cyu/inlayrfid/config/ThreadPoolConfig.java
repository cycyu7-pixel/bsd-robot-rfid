package com.cyu.inlayrfid.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性扫描任务线程池。
 * <p>
 * 核心 1 线程，最大 3 线程，排队队列容量 5，
 * 超出时直接拒绝（由调用方兜底，返回 too many scans 错误）。
 */
@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Bean("scanExecutor")
    public ThreadPoolExecutor scanExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,                               // corePoolSize
                3,                               // maximumPoolSize
                60L,                             // keepAliveTime
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5),    // 最多 5 条排队
                new NamedDaemonThreadFactory("scan-worker"),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 线程池指标监控（每 30 秒打印一次）
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("扫描任务队列已满（排队中: {}, 活跃: {}），请求被拒绝",
                    e.getQueue().size(), e.getActiveCount());
            throw new RejectedExecutionException("扫描任务过多，请稍后重试");
        });

        log.info("扫描线程池初始化完成: core=1, max=3, queue=5");
        return executor;
    }

    /**
     * 自定义拒绝异常，便于 Controller 统一捕获。
     */
    public static class RejectedExecutionException extends RuntimeException {
        public RejectedExecutionException(String message) {
            super(message);
        }
    }

    /**
     * 用于回调调用方的 HTTP 客户端。
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    private static class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}