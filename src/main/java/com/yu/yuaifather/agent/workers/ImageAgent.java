package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.tools.ImageSearchTool;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.config.ImageProperties;
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
 * ImageAgent：消费 plan.imageSlots → 并发搜图 → 输出 imageMap。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.tool.system.ImageSearchTool}，请使用 ImageSearchTool 替代。
 */
@Deprecated
@Slf4j
@Component
public class ImageAgent implements Agent {

    public static final String AGENT_NAME = "ImageAgent";

    private final ImageSearchTool imageSearchTool;
    private final ImageProperties properties;

    @Autowired
    public ImageAgent(ImageSearchTool imageSearchTool, ImageProperties properties) {
        this.imageSearchTool = imageSearchTool;
        this.properties = properties;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        GenerationPlan plan = ctx.getBlackboard().getPlan();
        List<GenerationPlan.ImageSlot> slots = plan == null ? null : plan.getImageSlots();

        // 无图片槽直接结束，不发起任何搜图请求
        if (slots == null || slots.isEmpty()) {
            log.info("ImageAgent 无 imageSlot，跳过 appId={}", ctx.getAppId());
            return Flux.just(AgentEvent.status(AGENT_NAME, "无图片槽，跳过"));
        }

        int concurrency = Math.max(1, properties.getReplace().getConcurrency());

        return Flux.just(AgentEvent.status(AGENT_NAME, "开始搜图，共 " + slots.size() + " 个槽"))
                .concatWith(
                        // 并发搜图：flatMap 的第二个参数 concurrency 控制最大同时执行数
                        // Mono.fromCallable 将阻塞的 HTTP 搜图调用包装为响应式，
                        // subscribeOn 保证其在弹性线程池执行，不阻塞 Reactor 调度线程
                        Flux.fromIterable(slots)
                                .flatMap(slot -> Mono.fromCallable(() -> {
                                    try {
                                        String url = imageSearchTool.search(slot.getKeyword());
                                        return Map.entry(slot.getKeyword(), url == null ? "" : url);
                                    } catch (Exception e) {
                                        log.warn("搜图失败 slotId={} keyword={} msg={}",
                                                slot.getSlotId(), slot.getKeyword(), e.getMessage());
                                        return Map.entry(slot.getKeyword(), "");
                                    }
                                }).subscribeOn(Schedulers.boundedElastic()), concurrency)
                                // 过滤搜图失败（URL 为空）的条目
                                .filter(entry -> !entry.getValue().isEmpty())
                                // 收集为 keyword → url 的 Map（全部完成后才触发下游）
                                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                .flatMapMany(imageMap -> {
                                    // 写入黑板，供 CoderAgent 的 ImagePlaceholderReplacer 使用
                                    ctx.getBlackboard().putImageMap(imageMap);
                                    log.info("ImageAgent 搜图完成 appId={} 命中={}/{}",
                                            ctx.getAppId(), imageMap.size(), slots.size());
                                    return Flux.just(AgentEvent.builder()
                                            .type(AgentEvent.EventType.STATUS_CHANGE)
                                            .agentName(AGENT_NAME)
                                            .content("搜图完成，命中 " + imageMap.size()
                                                    + "/" + slots.size() + " 项")
                                            .data(new HashMap<>(imageMap))
                                            .build());
                                })
                                // 搜图全部失败（imageMap 为空）时兜底：写空 map + 推完成事件，不终止流水线
                                .switchIfEmpty(Flux.defer(() -> {
                                    log.warn("ImageAgent 所有 slot 搜图均失败 appId={}", ctx.getAppId());
                                    ctx.getBlackboard().putImageMap(new HashMap<>());
                                    return Flux.just(AgentEvent.status(AGENT_NAME, "搜图全部失败，继续流水线"));
                                }))
                );
    }
}
