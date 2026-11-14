package com.yu.yuaifather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置。
 * <p>
 * 替代直接使用 {@code Thread.startVirtualThread()} 的无界创建方式，
 * 提供有界线程池 + 队列 + 拒绝策略的健壮异步执行能力。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 截图配置（{@code app.screenshot.executor.*}），默认值与改造前一致：core=2 / max=4 / queue=20。 */
    private final ScreenshotProperties screenshotProperties;

    public AsyncConfig(ScreenshotProperties screenshotProperties) {
        this.screenshotProperties = screenshotProperties;
    }

    @Bean(name = "screenshotExecutor")
    public Executor screenshotExecutor() {
        // 2C4G 单机部署可通过配置把截图线程池压到 1，默认值保持不变
        ScreenshotProperties.Executor config = screenshotProperties.getExecutor() == null
                ? new ScreenshotProperties.Executor()
                : screenshotProperties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix("screenshot-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "preferenceExecutor")
    public Executor preferenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("preference-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "buildExecutor")
    public Executor buildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("build-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
