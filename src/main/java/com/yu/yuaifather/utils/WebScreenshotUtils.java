package com.yu.yuaifather.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.config.ScreenshotProperties;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.media.screenshot.ChromeRuntime;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 截图工具（Spring 单例 Bean）。
 * <p>
 * 改造点：
 * <ul>
 *   <li>由静态工具类改为实例 Bean，调用方通过注入使用；</li>
 *   <li>删除 Windows 专用 chromedriver.exe 路径硬编码，驱动与 Chrome 可执行文件统一由
 *       {@link ChromeRuntime} 按 {@code app.screenshot.*} 配置解析；</li>
 *   <li>每次截图仍创建独立 WebDriver 并即时销毁，应用关闭时由 {@link #destroy()} 兜底释放残留实例。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebScreenshotUtils {

    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 900;

    private final ScreenshotProperties screenshotProperties;
    private final ChromeRuntime chromeRuntime;

    /** 当前存活（尚未 quit）的 WebDriver 实例；截图线程池并发执行时可能有多个，关闭时兜底释放。 */
    private final Set<WebDriver> activeDrivers = ConcurrentHashMap.newKeySet();

    public WebScreenshotUtils(ScreenshotProperties screenshotProperties, ChromeRuntime chromeRuntime) {
        this.screenshotProperties = screenshotProperties;
        this.chromeRuntime = chromeRuntime;
    }

    /**
     * 应用关闭时兜底销毁未正常关闭的 WebDriver 实例。
     */
    @PreDestroy
    public void destroy() {
        if (activeDrivers.isEmpty()) {
            return;
        }
        log.info("应用关闭，释放 {} 个未关闭的 WebDriver 实例", activeDrivers.size());
        for (WebDriver driver : activeDrivers) {
            quitQuietly(driver);
        }
        activeDrivers.clear();
    }

    /**
     * 生成网页截图
     *
     * @param webUrl 要截图的网址
     * @return 压缩后的截图文件路径，失败返回 null
     */
    public String saveWebPageScreenshot(String webUrl) {
        // 非空校验
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页截图失败，url为空");
            return null;
        }

        WebDriver driver = null;
        try {
            // 每次调用创建新的 WebDriver 实例
            driver = getWebDriver();
            activeDrivers.add(driver);

            // 创建临时目录
            String rootPath = System.getProperty("user.dir") + "/tmp/screenshots/" + UUID.randomUUID().toString().substring(0, 8);
            FileUtil.mkdir(rootPath);

            // 图片后缀
            final String IMAGE_SUFFIX = ".png";
            // 原始图片保存路径
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + IMAGE_SUFFIX;

            // 访问网页
            driver.get(webUrl);

            // 等待网页加载
            waitForPageLoad(driver);

            // 截图
            byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);

            // 保存原始图片
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功：{}", imageSavePath);

            // 压缩图片
            final String COMPRESS_SUFFIX = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + COMPRESS_SUFFIX;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功：{}", compressedImagePath);

            // 删除原始图片
            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败：{}", webUrl, e);
            return null;
        } finally {
            // 关闭当前 WebDriver 实例
            if (driver != null) {
                quitQuietly(driver);
                activeDrivers.remove(driver);
            }
        }
    }

    /**
     * 获取 WebDriver 实例。
     * <p>驱动路径由 {@link ChromeRuntime#resolveDriver()} 按配置解析（显式 driver-path → WebDriverManager），
     * Chrome 可执行文件由 {@link ChromeRuntime#resolveChromeBinary()} 解析（显式 chrome-binary → 自动探测）。</p>
     *
     * @return WebDriver 实例
     */
    private WebDriver getWebDriver() {
        try {
            log.debug("截图配置: chromeBinary={}, driverPath={}, useWebDriverManager={}",
                    screenshotProperties.getChromeBinary(), screenshotProperties.getDriverPath(),
                    screenshotProperties.isUseWebDriverManager());
            // 1) 解析 ChromeDriver（显式 driver-path 优先，否则 WebDriverManager）
            chromeRuntime.resolveDriver();

            // 2) 配置 Chrome 选项，显式指定 Chrome 路径（不在 PATH 时 ChromeDriver 找不到）
            ChromeOptions options = new ChromeOptions();
            String chromeBinary = chromeRuntime.resolveChromeBinary();
            if (StrUtil.isNotBlank(chromeBinary)) {
                options.setBinary(chromeBinary);
            }
            options.addArguments("--headless=new");    // 新版无头模式
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-extensions");
            options.addArguments("--remote-allow-origins=*");
            options.addArguments(String.format("--window-size=%d,%d", DEFAULT_WIDTH, DEFAULT_HEIGHT));
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + System.getProperty("webdriver.chrome.version", "149.0.0.0") + " Safari/537.36");

            // 3) 创建驱动
            WebDriver driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败: " + e.getMessage());
        }
    }

    /**
     * 关闭 WebDriver，异常仅记录日志（用于 finally 与关闭钩子）。
     */
    private void quitQuietly(WebDriver webDriver) {
        try {
            webDriver.quit();
        } catch (Exception e) {
            log.warn("关闭 WebDriver 时出现异常: {}", e.getMessage());
        }
    }

    /**
     * 保存图片到文件
     *
     * @param imageBytes 图片字节数据
     * @param imagePath 保存路径
     */
    private void saveImage(byte[] imageBytes, String imagePath) {
        try {
            FileUtil.writeBytes(imageBytes, imagePath);
        } catch (Exception e) {
            log.error("保存图片失败：{}", imagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    /**
     * 压缩图片
     *
     * @param originImagePath 原始图片路径
     * @param compressedImagePath 压缩后图片路径
     */
    private void compressImage(String originImagePath, String compressedImagePath) {
        // 压缩图片质量（0.1 = 10% 质量）
        final float COMPRESSION_QUALITY = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originImagePath),
                    FileUtil.file(compressedImagePath),
                    COMPRESSION_QUALITY
            );
        } catch (Exception e) {
            log.error("压缩图片失败：{} -> {}", originImagePath, compressedImagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }

    /**
     * 等待页面加载完成
     *
     * @param webDriver WebDriver 实例
     */
    private void waitForPageLoad(WebDriver webDriver) {
        try {
            // 创建等待页面加载对象
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
            // 等待 document.readyState 为 complete
            wait.until(driver -> ((JavascriptExecutor) driver)
                    .executeScript("return document.readyState").
                    equals("complete")
            );
            // 额外等待一段时间，确保动态内容加载完成
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常，继续执行截图", e);
        }
    }
}
