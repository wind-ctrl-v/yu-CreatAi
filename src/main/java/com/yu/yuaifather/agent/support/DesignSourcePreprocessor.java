package com.yu.yuaifather.agent.support;

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
 * 把原始输入（HTML / 截图）归一化为 {@code DesignExtractorAgent} 可消费的「结构化页面摘要」：
 * <ul>
 *   <li><b>HTML → 摘要</b>：抽取 DOM 结构骨架、关键 CSS（配色/字号/间距/圆角/阴影）、组件线索，
 *       <b>剥离业务文案与一次性内容</b>（呼应 2.3 提炼三原则），不把原始 HTML 直接喂给 LLM
 *       （呼应 2.6 prompt injection 隔离）；</li>
 *   <li><b>截图 → 描述</b>：视觉模型为<b>可选开关</b>，默认关闭（{@code design.extractor.vision-enabled=false}），
 *       关闭时不处理纯截图，保证零额外依赖与成本可控。</li>
 * </ul>
 *
 * <h3>安全（呼应 2.6 / SSRF 规则）</h3>
 * <ul>
 *   <li>不在本类内执行任何页面脚本（{@code <script>} 直接剥离）；</li>
 *   <li><b>默认不主动抓取 URL</b>：需访问任何内部域名前必须先经用户确认；常见内网段与
 *       {@code 9.* / 10.* / 11.* / 21.* / 30.*} 风险段一律拒绝；</li>
 *   <li>不持久化 cookie / token / 用户私有内容。</li>
 * </ul>
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

    /** 视觉模型开关，默认关闭。 */
    @Value("${design.extractor.vision-enabled:false}")
    private boolean visionEnabled;

    /**
     * 把原始 HTML 归一化为结构化页面摘要（已剥离脚本与业务文案）。
     *
     * @param rawHtml 已获取的页面 HTML（注意：本类不负责抓取，抓取需另行遵守 SSRF 规则）
     * @return 结构化摘要文本，可安全作为「数据」注入 LLM（非指令）
     */
    public String summarizeHtml(String rawHtml) {
        if (StrUtil.isBlank(rawHtml)) {
            return "";
        }
        // 1) 剥离脚本，绝不执行也不纳入摘要
        String html = SCRIPT_BLOCK.matcher(rawHtml).replaceAll(" ");

        // 2) 抽取 <style> 内的关键 CSS 声明
        Set<String> cssDecls = new LinkedHashSet<>();
        Matcher sm = STYLE_BLOCK.matcher(html);
        while (sm.find()) {
            collectCssDecls(sm.group(1), cssDecls);
        }
        // 内联 style 属性中的声明
        collectCssDecls(html, cssDecls);

        // 3) 统计结构骨架与组件线索
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

        // 4) 组装摘要（限制长度，避免 token 膨胀；不含业务文案）
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
                    break; // 最多保留 40 条声明
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

    /**
     * 截图 → 结构化描述。视觉模型默认关闭。
     *
     * @throws UnsupportedOperationException 视觉模型未启用时抛出（调用方应改用 HTML 路径）
     */
    public String describeScreenshot(String screenshotRef) {
        if (!visionEnabled) {
            throw new UnsupportedOperationException(
                    "截图视觉解析未启用（design.extractor.vision-enabled=false），请改用 HTML 摘要路径");
        }
        // 启用时应在此接入视觉模型，输出布局/配色/组件结构化描述后再经摘要归一化。
        // 出于零额外依赖与安全考虑，本实现暂不内置具体视觉模型调用。
        throw new UnsupportedOperationException("视觉模型解析尚未接入具体实现");
    }

    /**
     * SSRF 防护：判断主机是否处于禁止访问的内网 / 保留 / 风险网段。
     * <p>任何返回 {@code true} 的主机在未经用户显式确认前一律拒绝抓取。
     */
    public static boolean isDisallowedHost(String host) {
        if (StrUtil.isBlank(host)) {
            return true;
        }
        String h = host.trim().toLowerCase();
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::1")) {
            return true;
        }
        // 常见内网段 + 额外风险段 9.* / 10.* / 11.* / 21.* / 30.*
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
