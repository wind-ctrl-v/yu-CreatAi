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

        // 原子操作：获取或初始化计数器，同时检查上限。
        // ConcurrentHashMap.compute() 对同一 key 是串行化的，保证内部 thread-safe。
        final boolean[] incremented = {false};
        AtomicInteger toolCalls = counts.compute(toolName, (k, v) -> {
            if (v == null) {
                incremented[0] = true;
                return new AtomicInteger(1);
            }
            int current = v.get();
            if (current >= maxCallsPerTool) {
                return v; // 已达上限，不递增
            }
            v.incrementAndGet();
            incremented[0] = true;
            return v;
        });

        if (!incremented[0]) {
            log.warn("ToolRegistry 拒绝调用 agent={} tool={}（超出单 Tool 次数 {}）", agentName, toolName, maxCallsPerTool);
            return false;
        }

        // 检查总调用次数（非原子快照，但用于限流场景已足够）
        int totalCalls = counts.values().stream().mapToInt(AtomicInteger::get).sum();
        if (totalCalls > maxCallsPerRun) {
            toolCalls.decrementAndGet(); // 只回滚确实递增过的计数
            log.warn("ToolRegistry 拒绝调用 agent={} tool={}（超出总次数 {}）", agentName, toolName, maxCallsPerRun);
            return false;
        }
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
