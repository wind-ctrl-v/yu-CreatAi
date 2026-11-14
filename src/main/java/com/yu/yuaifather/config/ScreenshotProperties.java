package com.yu.yuaifather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 截图（headless Chrome）配置。绑定 {@code app.screenshot.*}。
 * <p>
 * 默认值与改造前一致；生产（2C4G）通过外置配置显式指定 Chrome/驱动路径并把线程池压到 1。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.screenshot")
public class ScreenshotProperties {

    /** 显式指定 Chrome/Chromium 可执行文件路径，空=自动探测。 */
    private String chromeBinary = "";

    /** 显式指定 chromedriver 可执行文件路径，空=走 WebDriverManager。 */
    private String driverPath = "";

    /** 是否允许 WebDriverManager 自动下载/匹配驱动（内网/大陆网络建议 false + 显式 driver-path）。 */
    private boolean useWebDriverManager = true;

    /** 截图异步线程池配置（默认等于改造前的 core=2/max=4/queue=20）。 */
    private Executor executor = new Executor();

    @Data
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 20;
    }
}
