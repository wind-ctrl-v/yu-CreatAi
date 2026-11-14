package com.yu.yuaifather.utils;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 截图集成测试（手工执行）。
 * <p>
 * 需要真实 Chrome/Chromium + 外网，CI 环境不满足，故 {@link Disabled}；
 * 本地手工验证时去掉注解执行即可（会真正启动 headless Chrome 截取外网页面）。
 * <p>
 * 改造后 {@code WebScreenshotUtils} 由静态工具类改为 Spring Bean，这里通过注入实例调用。
 */
@Slf4j
@SpringBootTest
@Disabled("需要 Chrome + 外网，手工执行")
public class WebScreenshotUtilsTest {

    @Autowired
    private WebScreenshotUtils webScreenshotUtils;

    @Test
    void saveWebPageScreenshot() {
        String testUrl = "https://www.codefather.cn";
        String webPageScreenshot = webScreenshotUtils.saveWebPageScreenshot(testUrl);
        assertNotNull(webPageScreenshot);
    }
}
