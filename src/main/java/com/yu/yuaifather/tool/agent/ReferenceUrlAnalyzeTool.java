package com.yu.yuaifather.tool.agent;

import com.yu.yuaifather.tool.core.AgentTool;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.core.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * ReferenceUrlAnalyzeTool：分析参考 URL 页面风格（Agent Tool）。
 * <p>
 * <b>安全约束</b>：
 * <ul>
 *   <li>禁止访问内网地址（10.*, 9.*, 11.*, 21.*, 30.*, localhost）</li>
 *   <li>禁止 file:// 协议</li>
 *   <li>限制超时和响应大小</li>
 *   <li>只读，不写入</li>
 * </ul>
 */
@Slf4j
@Component
public class ReferenceUrlAnalyzeTool implements AgentTool<ReferenceUrlAnalyzeTool.Input, ReferenceUrlAnalyzeTool.Result> {

    public static final String TOOL_NAME = "ReferenceUrlAnalyzeTool";

    public record Input(String url) {}
    public record Result(String styleProfile, String colorScheme, String layoutSummary) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("分析参考 URL 的页面风格，提取调性、配色和布局特征。用户提供竞品链接时使用。");
    }

    @Override
    public Result execute(Input input, ToolContext context) {
        String url = input.url();
        if (url == null || url.isBlank()) {
            return new Result("unknown", "unknown", "");
        }

        // SSRF 防护
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return new Result("unknown", "unknown", "");

            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                log.warn("ReferenceUrlAnalyzeTool 拒绝非 http(s) 协议 url={}", url);
                return new Result("unknown", "unknown", "");
            }

            if (isInternalHost(host)) {
                log.warn("ReferenceUrlAnalyzeTool 拒绝内网地址 host={}", host);
                return new Result("unknown", "unknown", "");
            }
        } catch (Exception e) {
            log.warn("ReferenceUrlAnalyzeTool URL 解析失败 url={}", url, e);
            return new Result("unknown", "unknown", "");
        }

        // TODO: 实际抓取和分析
        log.info("ReferenceUrlAnalyzeTool 分析 url={}", url);
        return new Result("unknown", "unknown", "待实现");
    }

    private boolean isInternalHost(String host) {
        if (host == null) return true;
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) return true;
        if (host.startsWith("10.") || host.startsWith("9.")
                || host.startsWith("11.") || host.startsWith("21.") || host.startsWith("30.")) return true;
        return false;
    }
}
