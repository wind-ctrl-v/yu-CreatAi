package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.tool.engine.ImageSearchEngine;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.config.ImageProperties;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ImageSearchTool：消费 plan.imageSlots → 并发搜图 → 输出 imageMap（System Tool，零 LLM）。
 * <p>
 * 从 ImageAgent 改名重构而来。
 */
@Slf4j
@Component
public class ImageSearchTool implements Agent, SystemTool<java.util.List<GenerationPlan.ImageSlot>, java.util.Map<String, String>> {

    public static final String TOOL_NAME = "ImageSearchTool";

    private final ImageSearchEngine imageSearchTool;
    private final ImageProperties properties;

    @Autowired
    public ImageSearchTool(ImageSearchEngine imageSearchTool, ImageProperties properties) {
        this.imageSearchTool = imageSearchTool;
        this.properties = properties;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        GenerationPlan plan = ctx.getBlackboard().getPlan();
        List<GenerationPlan.ImageSlot> slots = plan == null ? null : plan.getImageSlots();
        if (slots == null || slots.isEmpty()) {
            log.info("ImageSearchTool 无 imageSlot，跳过 appId={}", ctx.getAppId());
            return Flux.just(AgentEvent.status(TOOL_NAME, "无图片槽，跳过"));
        }
        int concurrency = Math.max(1, properties.getReplace().getConcurrency());
        return Flux.just(AgentEvent.status(TOOL_NAME, "开始搜图，共 " + slots.size() + " 个槽"))
                .concatWith(
                        Flux.fromIterable(slots)
                                .flatMap(slot -> Mono.fromCallable(() -> {
                                    try {
                                        String url = imageSearchTool.search(slot.getKeyword());
                                        return Map.entry(slot.getKeyword(), url == null ? "" : url);
                                    } catch (Exception e) {
                                        log.warn("搜图失败 keyword={} msg={}", slot.getKeyword(), e.getMessage());
                                        return Map.entry(slot.getKeyword(), "");
                                    }
                                }).subscribeOn(Schedulers.boundedElastic()), concurrency)
                                .filter(entry -> !entry.getValue().isEmpty())
                                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                .flatMapMany(imageMap -> {
                                    ctx.getBlackboard().putImageMap(imageMap);
                                    log.info("ImageSearchTool 搜图完成 appId={} 命中={}/{}",
                                            ctx.getAppId(), imageMap.size(), slots.size());
                                    return Flux.just(AgentEvent.builder()
                                            .type(AgentEvent.EventType.STATUS_CHANGE)
                                            .agentName(TOOL_NAME)
                                            .content("搜图完成，命中 " + imageMap.size() + "/" + slots.size() + " 项")
                                            .data(new HashMap<>(imageMap))
                                            .build());
                                })
                                .switchIfEmpty(Flux.defer(() -> {
                                    log.warn("ImageSearchTool 所有 slot 搜图均失败 appId={}", ctx.getAppId());
                                    ctx.getBlackboard().putImageMap(new HashMap<>());
                                    return Flux.just(AgentEvent.status(TOOL_NAME, "搜图全部失败，继续流水线"));
                                }))
                );
    }

    /**
     * SystemTool 同步执行：根据图片槽列表搜图，返回 keyword → URL 映射。
     */
    @Override
    public java.util.Map<String, String> execute(java.util.List<GenerationPlan.ImageSlot> slots, ToolContext context) {
        java.util.Map<String, String> result = new HashMap<>();
        if (slots == null || slots.isEmpty()) {
            return result;
        }
        int concurrency = Math.max(1, properties.getReplace().getConcurrency());
        try {
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
            try {
                java.util.List<java.util.concurrent.Future<java.util.Map.Entry<String, String>>> futures = new java.util.ArrayList<>();
                for (GenerationPlan.ImageSlot slot : slots) {
                    futures.add(executor.submit(() -> {
                        try {
                            String url = imageSearchTool.search(slot.getKeyword());
                            return Map.entry(slot.getKeyword(), url == null ? "" : url);
                        } catch (Exception e) {
                            log.warn("搜图失败 keyword={} msg={}", slot.getKeyword(), e.getMessage());
                            return Map.entry(slot.getKeyword(), "");
                        }
                    }));
                }
                for (java.util.concurrent.Future<java.util.Map.Entry<String, String>> f : futures) {
                    try {
                        java.util.Map.Entry<String, String> entry = f.get();
                        if (!entry.getValue().isEmpty()) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                    } catch (Exception e) {
                        log.warn("搜图 Future 异常", e);
                    }
                }
            } finally {
                executor.shutdown();
            }
        } catch (Exception e) {
            log.warn("ImageSearchTool 同步搜图异常", e);
        }
        return result;
    }
}
