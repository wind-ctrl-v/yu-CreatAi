package com.yu.yuaifather.tool.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ToolRegistry：Agent Tool 注册中心，管理白名单和调用限制。
 * <p>
 * 提供方法：
 * <ul>
 *   <li>白名单注册：按 Agent Name 配置可调用的 Tool</li>
 *   <li>调用次数限制：单次 Agent 运行最多调用 maxCallsPerRun 次</li>
 *   <li>同 Tool 调用限制：同一 Tool 最多调用 maxCallsPerTool 次</li>
 *   <li>超时限制：单次调用最多 timeoutMs 毫秒</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    /** Agent → 可调用 Tool 列表映射。 */
    private final Map<String, Set<String>> whitelist = new ConcurrentHashMap<>();

    /** 当前运行中每个 Agent 的已调用 Tool 及其次数。 */
    private final Map<String, Map<String, AtomicInteger>> callCounts = new ConcurrentHashMap<>();

    private int maxCallsPerRun = 3;
    private int maxCallsPerTool = 1;
    private int timeoutMs = 3000;

    /**
     * 注册白名单配置。
     */
    public void register(String agentName, String... toolNames) {
        whitelist.computeIfAbsent(agentName, k -> new LinkedHashSet<>())
                .addAll(Arrays.asList(toolNames));
        log.info("ToolRegistry 注册白名单 agent={} tools={}", agentName, Arrays.toString(toolNames));
    }

    /**
     * 检查 Agent 是否有权调用指定 Tool，以及是否超出次数限制。
     *
     * @return true = 允许调用
     */
    public boolean allow(String agentName, String toolName) {
        Set<String> allowed = whitelist.get(agentName);
        if (allowed == null || !allowed.contains(toolName)) {
            log.warn("ToolRegistry 拒绝调用 agent={} tool={}（不在白名单）", agentName, toolName);
            return false;
        }
        Map<String, AtomicInteger> counts = callCounts.computeIfAbsent(agentName, k -> new ConcurrentHashMap<>());
        int totalCalls = counts.values().stream().mapToInt(AtomicInteger::get).sum();
        if (totalCalls >= maxCallsPerRun) {
            log.warn("ToolRegistry 拒绝调用 agent={} tool={}（超出总次数 {}）", agentName, toolName, maxCallsPerRun);
            return false;
        }
        AtomicInteger toolCalls = counts.computeIfAbsent(toolName, k -> new AtomicInteger(0));
        if (toolCalls.get() >= maxCallsPerTool) {
            log.warn("ToolRegistry 拒绝调用 agent={} tool={}（超出单 Tool 次数 {}）", agentName, toolName, maxCallsPerTool);
            return false;
        }
        toolCalls.incrementAndGet();
        return true;
    }

    /** 每次 Agent 运行前重置计数。 */
    public void resetCounts(String agentName) {
        callCounts.remove(agentName);
    }

    /** 获取 Agent 白名单（供 PlanningAgent 获取可用工具列表）。 */
    public Set<String> getWhitelistFor(String agentName) {
        return Collections.unmodifiableSet(whitelist.getOrDefault(agentName, Collections.emptySet()));
    }

    /** 获取超时毫秒数。 */
    public int getTimeoutMs() {
        return timeoutMs;
    }
}
