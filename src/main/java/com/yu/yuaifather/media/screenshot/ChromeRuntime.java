package com.yu.yuaifather.media.screenshot;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.config.ScreenshotProperties;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Chrome 运行时解析器：统一解析 ChromeDriver 与 Chrome 可执行文件路径。
 * <p>
 * 解析优先级（对应 {@code app.screenshot.*} 配置）：
 * <ol>
 *   <li>显式配置的 {@code driver-path} / {@code chrome-binary}（生产、内网环境推荐，避免联网下载）；</li>
 *   <li>未显式配置时，ChromeDriver 走 WebDriverManager 自动下载/匹配，Chrome 可执行文件走内置自动探测列表；</li>
 *   <li>仍无法解析时抛出带操作提示的 {@link BusinessException}。</li>
 * </ol>
 * 改造前 {@code WebScreenshotUtils} 中硬编码的 Windows chromedriver.exe 路径已移除。
 */
@Slf4j
@Component
public class ChromeRuntime {

    /** Selenium 使用的 ChromeDriver 系统属性名。 */
    private static final String CHROME_DRIVER_PROPERTY = "webdriver.chrome.driver";

    /** Chrome 可执行文件自动探测列表（与改造前一致）。 */
    private static final String[] DEFAULT_CHROME_PATHS = {
            // Windows
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            // macOS
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            // Linux
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium-browser",
            "/usr/bin/chromium"
    };

    private final ScreenshotProperties screenshotProperties;

    public ChromeRuntime(ScreenshotProperties screenshotProperties) {
        this.screenshotProperties = screenshotProperties;
    }

    /**
     * 解析并准备 ChromeDriver。
     * <p>显式 {@code driver-path} 优先；未配置（或配置路径不存在）时按 {@code use-web-driver-manager} 决定是否走 WebDriverManager。</p>
     *
     * @return 解析出的 ChromeDriver 路径；WebDriverManager 未导出系统属性时可能为 {@code null}
     *         （此时交由 Selenium 自带的驱动管理兜底）
     * @throws BusinessException 无法解析驱动时抛出（消息包含可操作的配置提示）
     */
    public String resolveDriver() {
        // 1) 显式配置优先：生产/内网环境直接用本地 chromedriver，避免 WebDriverManager 联网
        String configuredPath = StrUtil.trimToNull(screenshotProperties.getDriverPath());
        if (configuredPath != null) {
            File driverFile = new File(configuredPath);
            if (driverFile.exists() && driverFile.isFile()) {
                String absolutePath = driverFile.getAbsolutePath();
                System.setProperty(CHROME_DRIVER_PROPERTY, absolutePath);
                log.info("使用显式配置的 ChromeDriver: {}", absolutePath);
                return absolutePath;
            }
            log.warn("显式配置的 ChromeDriver 不存在: {}，将尝试其它解析方式", configuredPath);
        }
        // 2) WebDriverManager 自动下载/匹配系统 Chrome 版本
        if (screenshotProperties.isUseWebDriverManager()) {
            try {
                WebDriverManager.chromedriver().setup();
                String resolvedPath = System.getProperty(CHROME_DRIVER_PROPERTY);
                log.info("WebDriverManager 已自动匹配 ChromeDriver: {}", resolvedPath);
                return resolvedPath;
            } catch (Exception e) {
                log.warn("WebDriverManager 自动匹配 ChromeDriver 失败: {}", e.getMessage());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "ChromeDriver 解析失败，请配置 app.screenshot.driver-path 指向本地 chromedriver，"
                                + "或检查网络后开启 app.screenshot.use-web-driver-manager: " + e.getMessage());
            }
        }
        // 3) 无可用驱动：给出明确可操作的提示
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "ChromeDriver 未找到，请配置 app.screenshot.driver-path（或设置环境变量 APP_SCREENSHOT_DRIVER_PATH）");
    }

    /**
     * 解析 Chrome/Chromium 可执行文件路径。
     * <p>显式 {@code chrome-binary} 优先；未配置（或配置路径不存在）时回退到内置自动探测列表。
     * Chrome 不在 PATH 时 ChromeDriver 需要显式 binary，找不到时返回 {@code null} 由 ChromeDriver 使用默认位置。</p>
     *
     * @return Chrome 可执行文件路径；未找到时返回 {@code null}
     */
    public String resolveChromeBinary() {
        // 1) 显式配置优先
        String configuredPath = StrUtil.trimToNull(screenshotProperties.getChromeBinary());
        if (configuredPath != null) {
            if (new File(configuredPath).exists()) {
                log.info("使用显式配置的 Chrome: {}", configuredPath);
                return configuredPath;
            }
            log.warn("显式配置的 Chrome 可执行文件不存在: {}，回退自动探测", configuredPath);
        }
        // 2) 自动探测常见安装位置（Windows / macOS / Linux）
        for (String candidate : DEFAULT_CHROME_PATHS) {
            if (new File(candidate).exists()) {
                log.info("自动探测到 Chrome: {}", candidate);
                return candidate;
            }
        }
        log.warn("未探测到 Chrome 可执行文件，交由 ChromeDriver 使用默认浏览器路径");
        return null;
    }
}
