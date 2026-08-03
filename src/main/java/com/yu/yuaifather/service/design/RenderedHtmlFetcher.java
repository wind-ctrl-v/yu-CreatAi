package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.shared.security.SafeUrlValidator;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * RenderedHtmlFetcher：外部页面 HTML 抓取器（覆盖度优化 阶段三）。
 * <p>
 * 双模式抓取：
 * <ul>
 *   <li><b>渲染抓取</b>（默认）：headless Chrome 执行 JS 后取 pageSource，
 *       应对 Awwwards / Dribbble 等重 JS 设计站点（静态抓取只能拿到空壳）；</li>
 *   <li><b>静态抓取</b>（降级）：RestTemplate 直接拉取，渲染失败时自动回退。</li>
 * </ul>
 *
 * <h3>安全（SSRF）</h3>
 * <ul>
 *   <li>所有 URL 请求前经 {@link SafeUrlValidator}（仅公网 HTTP/HTTPS，
 *       拒绝内网与保留地址段）；</li>
 *   <li>渲染模式在页面加载完成后二次校验 {@code driver.getCurrentUrl()}，
 *       防 302 跳转绕过首跳校验（与 {@code WebScreenshotUtils} 同一防护口径）。</li>
 * </ul>
 */
@Slf4j
@Component
public class RenderedHtmlFetcher {

    /** 爬虫标识 UA（礼貌策略：可被目标站点识别与封禁，不伪装成浏览器用户）。 */
    private static final String CRAWLER_UA =
            "CreatAiDesignBot/1.0 (+design-fingerprint-crawler)";

    private final RestTemplate restTemplate;

    public RenderedHtmlFetcher() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 抓取页面 HTML。
     *
     * @param url    目标 URL（仅公网 HTTP/HTTPS）
     * @param render 是否优先使用无头浏览器渲染
     * @return 页面 HTML；失败返回 {@code null}（调用方按跳过处理）
     */
    public String fetch(String url, boolean render) {
        try {
            SafeUrlValidator.validate(url);
        } catch (Exception e) {
            log.warn("URL 未通过 SSRF 校验，拒绝抓取 url={} msg={}", url, e.getMessage());
            return null;
        }
        if (render) {
            String html = fetchRendered(url);
            if (StrUtil.isNotBlank(html)) {
                return html;
            }
            log.warn("渲染抓取失败，降级静态抓取 url={}", url);
        }
        return fetchStatic(url);
    }

    /**
     * 静态抓取（RestTemplate）。
     */
    private String fetchStatic(String url) {
        try {
            byte[] body = restTemplate.execute(url, HttpMethod.GET,
                    request -> request.getHeaders().set(HttpHeaders.USER_AGENT, CRAWLER_UA),
                    response -> response.getBody().readAllBytes());
            if (body == null || body.length == 0) {
                return null;
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("静态抓取失败 url={} msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 渲染抓取（headless Chrome，每次调用独立实例，用完即销毁）。
     */
    private String fetchRendered(String url) {
        WebDriver driver = null;
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox",
                    "--disable-dev-shm-usage", "--disable-extensions",
                    "--window-size=1600,900",
                    "--user-agent=" + CRAWLER_UA);
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            driver.get(url);
            new WebDriverWait(driver, Duration.ofSeconds(10)).until(d ->
                    ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
            // 给 SPA 动态内容额外渲染时间
            Thread.sleep(1500);
            // 二次校验最终落地 URL，防重定向到内网
            SafeUrlValidator.validate(driver.getCurrentUrl());
            return driver.getPageSource();
        } catch (Exception e) {
            log.error("渲染抓取失败 url={} msg={}", url, e.getMessage());
            return null;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("关闭 WebDriver 异常: {}", e.getMessage());
                }
            }
        }
    }
}
