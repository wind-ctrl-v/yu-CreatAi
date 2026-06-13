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
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ImageAgent：消费 plan.imageSlots → 并发搜图 → 输出 imageMap。
 * <p>
 * 直接以代码方式调度 {@link ImageSearchTool}（无需 LLM 介入），
 * 因为该任务是确定性的，不需要语义推理。提示词文件用作 LLM 兜底模式的参考。
 */
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
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "开始搜图"));
            GenerationPlan plan = ctx.getBlackboard().getPlan();
            List<GenerationPlan.ImageSlot> slots = plan == null ? null : plan.getImageSlots();
            if (slots == null || slots.isEmpty()) {
                log.info("ImageAgent 无 imageSlot，跳过 appId={}", ctx.getAppId());
                sink.next(AgentEvent.status(AGENT_NAME, "无图片槽，跳过"));
                sink.complete();
                return;
            }
            Map<String, String> imageMap = new ConcurrentHashMap<>();
            int concurrency = Math.max(1, properties.getReplace().getConcurrency());
            Flux.fromIterable(slots)
                    .parallel(concurrency)
                    .runOn(Schedulers.boundedElastic())
                    .doOnNext(slot -> {
                        try {
                            String url = imageSearchTool.search(slot.getKeyword());
                            if (url != null) {
                                imageMap.put(slot.getSlotId(), url);
                                // 同时按 keyword 索引，便于 {{IMG:keyword}} 模板直接命中
                                imageMap.put(slot.getKeyword(), url);
                            }
                        } catch (Exception e) {
                            log.warn("搜图失败 slotId={} keyword={} msg={}",
                                    slot.getSlotId(), slot.getKeyword(), e.getMessage());
                        }
                    })
                    .sequential()
                    .blockLast();
            ctx.getBlackboard().putImageMap(imageMap);
            sink.next(AgentEvent.builder()
                    .type(AgentEvent.EventType.STATUS_CHANGE)
                    .agentName(AGENT_NAME)
                    .content("搜图完成，命中 " + imageMap.size() + " 项")
                    .data(new HashMap<>(imageMap))
                    .build());
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
