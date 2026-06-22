package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DesignSourcePreprocessor：指纹输入前置链路（阶段二，见计划 2.7.1）。
 * <p>
 * 把原始输入（HTML / 截图）归一化为 DesignExtractorAgent 可消费的「结构化页面摘要」。
 *
 * <h3>安全（SSRF 规则）</h3>
 * 不在本类内执行任何页面脚本；默认不主动抓取 URL；常见内网段与
 * {@code 9.* / 10.* / 11.* / 21.* / 30.*} 风险段一律拒绝。
 */
@Slf4j
@Component
public class DesignSourcePreprocessor {

    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
    private static final Pattern STYLE_BLOCK = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
    private static final Pattern STRUCTURAL_TAG = Pattern.compile(
            "(?i)<(header|nav|main|section|article|aside|footer|h1|h2|h3|form|button|table|ul|ol)\\b");
    private static final Pattern IMG_TAG = Pattern.compile("(?i)<img\\b");
    private static final Pattern CSS_DECL = Pattern.compile(
            "(?i)(color|background|background-color|font-size|font-family|padding|margin|border-radius|box-shadow|line-height|gap)\\s*:\\s*([^;{}]{1,80})");

    @Value("${design.extractor.vision-enabled:false}")
    private boolean visionEnabled;

    public String summarizeHtml(String rawHtml) {
        if (StrUtil.isBlank(rawHtml)) {
            return "";
        }
        String html = SCRIPT_BLOCK.matcher(rawHtml).replaceAll(" ");

        Set<String> cssDecls = new LinkedHashSet<>();
        Matcher sm = STYLE_BLOCK.matcher(html);
        while (sm.find()) {
            collectCssDecls(sm.group(1), cssDecls);
        }
        collectCssDecls(html, cssDecls);

        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        Matcher tm = STRUCTURAL_TAG.matcher(html);
        while (tm.find()) {
            String tag = tm.group(1).toLowerCase();
            tagCounts.merge(tag, 1, Integer::sum);
        }
        int imgCount = 0;
        Matcher im = IMG_TAG.matcher(html);
        while (im.find()) {
            imgCount++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【结构骨架】");
        if (tagCounts.isEmpty()) {
            sb.append("(未识别到结构化标签)");
        } else {
            tagCounts.forEach((k, v) -> sb.append(k).append("x").append(v).append(" "));
        }
        if (imgCount > 0) {
            sb.append("img x").append(imgCount).append(" ");
        }
        sb.append("\n【关键样式】");
        if (cssDecls.isEmpty()) {
            sb.append("(未识别到内联/嵌入样式)");
        } else {
            int i = 0;
            for (String decl : cssDecls) {
                if (i++ >= 40) {
                    break;
                }
                sb.append(decl).append("; ");
            }
        }
        return sb.toString();
    }

    private void collectCssDecls(String css, Set<String> out) {
        if (StrUtil.isBlank(css)) {
            return;
        }
        Matcher m = CSS_DECL.matcher(css);
        while (m.find()) {
            out.add(m.group(1).toLowerCase() + ": " + m.group(2).trim());
        }
    }

    public String describeScreenshot(String screenshotRef) {
        if (!visionEnabled) {
            throw new UnsupportedOperationException(
                    "截图视觉解析未启用（design.extractor.vision-enabled=false），请改用 HTML 摘要路径");
        }
        throw new UnsupportedOperationException("视觉模型解析尚未接入具体实现");
    }

    /**
     * SSRF 防护：判断主机是否处于禁止访问的内网 / 保留 / 风险网段。
     */
    public static boolean isDisallowedHost(String host) {
        if (StrUtil.isBlank(host)) {
            return true;
        }
        String h = host.trim().toLowerCase();
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::1")) {
            return true;
        }
        String[] riskyPrefixes = {
                "10.", "11.", "9.", "21.", "30.",
                "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
                "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
                "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
                "169.254."
        };
        for (String p : riskyPrefixes) {
            if (h.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
