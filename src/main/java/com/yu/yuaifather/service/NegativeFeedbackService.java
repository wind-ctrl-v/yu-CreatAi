package com.yu.yuaifather.service;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.entity.UserDesignEvent;
import com.yu.yuaifather.worker.PreferenceUpdateWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 负反馈处理服务（P0-残留-1 补全）。
 * <p>
 * 统一承接前端 5 类负反馈事件（UNDO / REVERT_TO_GENERATED / ABANDON /
 * REJECT_SUGGESTION / EXPLICIT_DISLIKE）的记录与偏好权重更新。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>所有事件均异步处理，不阻塞调用方主链路</li>
 *   <li>失败仅记日志，不抛异常（负反馈是辅助信号，不能影响主流程）</li>
 *   <li>事件始终落库（user_design_event），权重是否更新由 {@link PreferenceUpdateWorker} 决定</li>
 * </ul>
 */
@Slf4j
@Service
public class NegativeFeedbackService {

    private final PreferenceUpdateWorker preferenceUpdateWorker;
    private final PromptPreferenceExtractor promptPreferenceExtractor;
    private final AppService appService;

    public NegativeFeedbackService(PreferenceUpdateWorker preferenceUpdateWorker,
                                    PromptPreferenceExtractor promptPreferenceExtractor,
                                    AppService appService) {
        this.preferenceUpdateWorker = preferenceUpdateWorker;
        this.promptPreferenceExtractor = promptPreferenceExtractor;
        this.appService = appService;
    }

    /**
     * 记录负反馈事件并更新偏好权重。
     *
     * @param userId     用户 ID
     * @param appId      应用 ID
     * @param eventType  事件类型（undo / revert_to_generated / abandon / reject_suggestion / explicit_dislike）
     * @param metadata   附加元数据（reason / tags / instruction 等）
     */
    public void recordNegativeEvent(Long userId, Long appId, String eventType, Map<String, Object> metadata) {
        if (userId == null || StrUtil.isBlank(eventType)) return;

        try {
            // 1. 记录事件（始终落库，不遗漏）
            UserDesignEvent event = new UserDesignEvent();
            event.setUserId(userId);
            event.setEventType(eventType);
            event.setGenerationId(appId);
            event.setCreateTime(LocalDateTime.now());
            if (metadata != null && !metadata.isEmpty()) {
                event.setMetadata(cn.hutool.json.JSONUtil.toJsonStr(metadata));
            }
            preferenceUpdateWorker.recordAndUpdate(event);

            // 2. 对于 explicit_dislike，如果有 tags 则额外用 LLM 异步提取
            if ("explicit_dislike".equals(eventType)) {
                @SuppressWarnings("unchecked")
                List<String> tags = metadata != null
                        ? (List<String>) metadata.getOrDefault("tags", List.of())
                        : List.of();
                String initPrompt = appId != null ? getInitPrompt(appId) : null;
                promptPreferenceExtractor.extractAndRecordNegativeAsync(userId, appId, initPrompt, tags);
            }

            log.info("[负反馈事件] userId={}, appId={}, type={}", userId, appId, eventType);
        } catch (Exception e) {
            log.warn("[负反馈记录失败] userId={}, appId={}, type={}", userId, appId, eventType, e);
        }
    }

    /**
     * 显式点踩的便捷方法。
     *
     * @param userId  用户 ID
     * @param appId   应用 ID
     * @param reason  点踩原因（style/layout/color/other）
     * @param tags    前端传入的标签（可选）
     */
    public void recordDislike(Long userId, Long appId, String reason, List<String> tags) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        if (StrUtil.isNotBlank(reason)) {
            metadata.put("reason", reason);
        }
        if (tags != null && !tags.isEmpty()) {
            metadata.put("tags", tags);
        }
        recordNegativeEvent(userId, appId, "explicit_dislike", metadata);
    }

    /** 获取应用的原始需求文本（用于 LLM 提取负向偏好标签） */
    private String getInitPrompt(Long appId) {
        try {
            var app = appService.getById(appId);
            return app != null ? app.getInitPrompt() : null;
        } catch (Exception e) {
            log.debug("获取应用 initPrompt 失败 appId={}", appId);
            return null;
        }
    }
}
