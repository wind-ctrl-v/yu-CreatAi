package com.yu.yuaifather.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增配置项绑定测试：确认 {@code application.yml} 中的键名/嵌套结构与各 Properties 类一致。
 * <p>
 * 只验证绑定，不启动完整 Spring 上下文（无数据库/Redis 依赖）。
 */
class PropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class)
            .withPropertyValues(
                    "codegen.build.lock-wait-seconds=120",
                    "codegen.build.install-timeout-seconds=900",
                    "codegen.build.build-timeout-seconds=450",
                    "codegen.build.cleanup-node-modules=true",
                    "app.screenshot.chrome-binary=/opt/chrome/chrome",
                    "app.screenshot.driver-path=/opt/chrome/chromedriver",
                    "app.screenshot.use-web-driver-manager=false",
                    "app.screenshot.executor.core-pool-size=1",
                    "app.screenshot.executor.max-pool-size=1",
                    "app.screenshot.executor.queue-capacity=5",
                    "agent.retrieve.vector.store=mysql",
                    "agent.retrieve.vector.reload-interval-ms=60000",
                    "agent.retrieve.vector.expected-dim=1024",
                    "agent.retrieve.vector.max-entries=5000",
                    "agent.retrieve.vector.resync-max=50",
                    "app.limits.generation.max-concurrent=2",
                    "app.limits.deploy.max-concurrent=1");

    @Test
    void shouldBindBuildAndScreenshotProperties() {
        runner.run(context -> {
            BuildProperties build = context.getBean(BuildProperties.class);
            assertEquals(120, build.getLockWaitSeconds());
            assertEquals(900, build.getInstallTimeoutSeconds());
            assertEquals(450, build.getBuildTimeoutSeconds());
            assertTrue(build.isCleanupNodeModules());

            ScreenshotProperties screenshot = context.getBean(ScreenshotProperties.class);
            assertEquals("/opt/chrome/chrome", screenshot.getChromeBinary());
            assertEquals("/opt/chrome/chromedriver", screenshot.getDriverPath());
            assertFalse(screenshot.isUseWebDriverManager());
            assertNotNull(screenshot.getExecutor());
            assertEquals(1, screenshot.getExecutor().getCorePoolSize());
            assertEquals(1, screenshot.getExecutor().getMaxPoolSize());
            assertEquals(5, screenshot.getExecutor().getQueueCapacity());
        });
    }

    @Test
    void shouldBindVectorStoreAndLimitsProperties() {
        runner.run(context -> {
            VectorStoreProperties vector = context.getBean(VectorStoreProperties.class);
            assertEquals("mysql", vector.getStore());
            assertEquals(60000L, vector.getReloadIntervalMs());
            assertEquals(1024, vector.getExpectedDim());
            assertEquals(5000, vector.getMaxEntries());
            assertEquals(50, vector.getResyncMax());

            LimitsProperties limits = context.getBean(LimitsProperties.class);
            assertEquals(2, limits.getLimits().get("generation").getMaxConcurrent());
            assertEquals(1, limits.getLimits().get("deploy").getMaxConcurrent());
        });
    }

    @Test
    void shouldUseBackwardCompatibleDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class)
                .run(context -> {
                    BuildProperties build = context.getBean(BuildProperties.class);
                    assertEquals(300, build.getLockWaitSeconds());
                    assertEquals(300, build.getInstallTimeoutSeconds());
                    assertEquals(180, build.getBuildTimeoutSeconds());
                    assertFalse(build.isCleanupNodeModules());

                    VectorStoreProperties vector = context.getBean(VectorStoreProperties.class);
                    assertEquals("pg", vector.getStore());
                    assertEquals(0, vector.getExpectedDim());

                    LimitsProperties limits = context.getBean(LimitsProperties.class);
                    assertTrue(limits.getLimits().isEmpty());
                });
    }

    @Configuration
    @EnableConfigurationProperties({BuildProperties.class, ScreenshotProperties.class,
            VectorStoreProperties.class, LimitsProperties.class})
    static class BindingConfig {
    }
}
