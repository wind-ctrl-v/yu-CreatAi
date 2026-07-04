package com.yu.yuaifather.service;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.entity.UserDesignEvent;
import com.yu.yuaifather.worker.PreferenceUpdateWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 应用相关的用户行为记录服务（从 {@code AppController} 下沉，见包结构优化计划问题 2）。
 * <p>
 * 承接原本直接写在 Controller 里的"用户设计行为事件记录"与"增量编辑结果判定"逻辑，
 * Controller 层只保留参数校验与调用，不再直接构造领域实体或做业务判定。
 * <p>
 * 与 {@link com.yu.yuaifather.worker.PreferenceUpdateWorker} 的关系：本类负责"根据业务场景构造
 * {@link UserDesignEvent} 并触发记录"，具体的偏好权重更新规则仍由 {@code PreferenceUpdateWorker} 承担，
 * 两者不重叠。
 */
@Slf4j
@Component
public class AppBehaviorService {

    /** 增量编辑成功的关键字（AI 被 Prompt 指示成功时会在响应中包含其中之一）。 */
    private static final String[] SUCCESS_KEYWORDS = {"修改完成", "修改成功", "写入成功", "已完成"};

    /** 增量编辑失败的关键字（命中任意一个则强制判定为失败，即使同时命中了成功关键字）。 */
    private static final String[] FAILURE_KEYWORDS = {"失败", "不存在", "错误"};

    private final PreferenceUpdateWorker preferenceUpdateWorker;
    private final PromptPreferenceExtractor promptPreferenceExtractor;

    @Autowired
    public AppBehaviorService(PreferenceUpdateWorker preferenceUpdateWorker,
                               PromptPreferenceExtractor promptPreferenceExtractor) {
        this.preferenceUpdateWorker = preferenceUpdateWorker;
        this.promptPreferenceExtractor = promptPreferenceExtractor;
    }

    /**
     * 记录一次"页面导出"用户行为事件（偏好闭环：page_exported，权重 +0.2）。
     * <p>
     * 记录失败仅打日志，不影响调用方主流程（与原 Controller 内 try-catch 语义一致）。
     *
     * @param userId 当前登录用户 ID
     * @param appId  应用 ID（作为 generationId 记录）
     */
    public void recordPageExportedEvent(Long userId, Long appId) {
        try {
            UserDesignEvent event = new UserDesignEvent();
            event.setUserId(userId);
            event.setEventType("page_exported");
            event.setGenerationId(appId);
            event.setCreateTime(LocalDateTime.now());
            preferenceUpdateWorker.recordAndUpdate(event);
        } catch (Exception e) {
            log.warn("下载行为记录失败，不影响主流程 appId={}", appId, e);
        }
    }

    /**
     * 记录一次"页面删除"用户行为事件（偏好闭环：page_deleted，负反馈信号）。
     * <p>
     * ★ N-3 修复（删除触发负反馈的边界控制）：
     * 根据删除原因判断是否计入负反馈：
     * <ul>
     *   <li>{@code test} — 测试性删除，不计入负反馈</li>
     *   <li>{@code correction} — 方向调整后删除，不计入负反馈</li>
     *   <li>{@code expire} — 草稿过期清理，不计入负反馈</li>
     *   <li>{@code dislike} — 真正不喜欢，计入负反馈 ✅</li>
     *   <li>{@code unknown} — 未知，保守策略：计入（避免漏掉真不喜欢）</li>
     * </ul>
     * 事件始终记录到 user_design_event，仅负反馈权重更新受原因控制。
     *
     * @param userId        当前登录用户 ID
     * @param appId         应用 ID
     * @param initPrompt    应用创建时的原始需求文本
     * @param deleteReason  删除原因（test/correction/expire/dislike/unknown）
     */
    public void recordPageDeletedEvent(Long userId, Long appId, String initPrompt, String deleteReason) {
        String reason = StrUtil.isBlank(deleteReason) ? "unknown" : deleteReason.toLowerCase();
        boolean shouldCountAsNegative = shouldCountAsNegativeFeedback(reason);

        try {
            UserDesignEvent event = new UserDesignEvent();
            event.setUserId(userId);
            event.setEventType("page_deleted");
            event.setGenerationId(appId);
            event.setCreateTime(LocalDateTime.now());
            // 记录删除原因和是否计入负反馈（始终落库，不遗漏）
            event.setMetadata(cn.hutool.json.JSONUtil.toJsonStr(java.util.Map.of(
                    "reason", reason,
                    "countAsNegative", shouldCountAsNegative
            )));
            preferenceUpdateWorker.recordAndUpdate(event);
        } catch (Exception e) {
            log.warn("删除行为记录失败，不影响主流程 appId={}", appId, e);
        }

        // 仅在应该计入负反馈时，异步提取负向偏好标签
        if (shouldCountAsNegative && StrUtil.isNotBlank(initPrompt)) {
            final Long uid = userId;
            Thread.ofVirtual().start(() -> {
                try {
                    promptPreferenceExtractor.extractAndRecordNegative(uid, initPrompt);
                } catch (Exception e) {
                    log.warn("删除应用偏好提取失败 appId={}", appId, e);
                }
            });
        } else {
            log.info("[删除判定] 不计入负反馈 userId={}, appId={}, reason={}", userId, appId, reason);
        }
    }

    /**
     * 判断删除是否应该计入负反馈（N-3 边界控制）。
     * <p>
     * 保守策略：test/correction/expire 不计入，dislike/unknown 计入。
     */
    private boolean shouldCountAsNegativeFeedback(String reason) {
        return switch (reason) {
            case "test", "correction", "expire" -> false;
            // dislike 和 unknown 都计入（unknown 保守策略：宁可多记也不漏）
            default -> true;
        };
    }

    /**
     * 根据 AI 增量编辑响应文本，判定本次修改是否成功。
     * <p>
     * 当前实现为关键字启发式匹配（AI 被 Prompt 指示成功时会回复固定措辞），
     * 属于过渡方案；长期看应考虑让 AI 返回结构化 JSON 而非依赖关键字匹配（见包结构优化计划问题 2 备注）。
     *
     * @param aiResult AI 增量编辑的原始响应文本
     * @return 是否判定为成功
     */
    public boolean judgeIncrementalEditSuccess(String aiResult) {
        if (aiResult == null) {
            return false;
        }
        boolean success = containsAny(aiResult, SUCCESS_KEYWORDS);
        return success && !containsAny(aiResult, FAILURE_KEYWORDS);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
