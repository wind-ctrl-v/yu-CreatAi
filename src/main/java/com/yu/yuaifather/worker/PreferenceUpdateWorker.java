package com.yu.yuaifather.worker;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.mapper.UserDesignEventMapper;
import com.yu.yuaifather.mapper.UserDesignProfileMapper;
import com.yu.yuaifather.mapper.UserPreferenceWeightMapper;
import com.yu.yuaifather.model.entity.UserDesignEvent;
import com.yu.yuaifather.model.entity.UserDesignProfile;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * PreferenceUpdateWorker：偏好更新异步 Worker。
 * <p>
 * 从 PreferenceUpdateService 改名重构而来。
 * <p>
 * ★ 偏好提取统一重构（2026-07-04）：用户修改指令的偏好提取全部走
 * {@link com.yu.yuaifather.service.PromptPreferenceExtractor}（LLM 提取，支持正负向），
 * 不再走前端规则上报路径。本类的 {@link #applyPreferenceUpdate} 仅保留以下两类场景：
 * <ul>
 *   <li>页面生命周期事件（page_saved/page_exported/page_deleted/page_regenerated）：
 *       作为弱正/负反馈信号，更新 {@code style:page_saved}/{@code style:page_exported} 等标签权重，
 *       仅作辅助信号，真正的偏好信号由 LLM 从修改指令中提取。</li>
 *   <li>结构化事件（color_changed/component_added/component_removed/layout_changed）：
 *       保留以兼容可能的历史调用，但当前前端已不再上报这些事件（已统一走 LLM 提取）。</li>
 * </ul>
 * <p>
 * 注：{@code user_rating_5}/{@code user_rating_1_2}（用户主动评分反馈）当前前端尚无对应埋点入口，
 * 故未在 {@link #applyPreferenceUpdate} 中实现对应分支（见修复计划 05）；若后续产品侧确认需要接入
 * 用户评分信号，请同时在 {@link com.yu.yuaifather.model.enums.EventTypeEnum} 补充枚举值。
 */
@Slf4j
@Service
public class PreferenceUpdateWorker {

    private final UserDesignEventMapper eventMapper;
    private final UserPreferenceWeightMapper weightMapper;
    private final UserDesignProfileMapper profileMapper;
    private final ObjectMapper objectMapper;
    private final Executor preferenceExecutor;

    /** 每 N 次权重更新触发一次 profileSummary 重算 */
    private static final int SUMMARY_REFRESH_INTERVAL = 10;

    public PreferenceUpdateWorker(UserDesignEventMapper eventMapper,
                                  UserPreferenceWeightMapper weightMapper,
                                  UserDesignProfileMapper profileMapper,
                                  ObjectMapper objectMapper,
                                  @Qualifier("preferenceExecutor") Executor preferenceExecutor) {
        this.eventMapper = eventMapper;
        this.weightMapper = weightMapper;
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
        this.preferenceExecutor = preferenceExecutor;
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordAndUpdate(UserDesignEvent event) {
        eventMapper.insert(event);
        try {
            applyPreferenceUpdate(event);
            // 每 N 次更新触发一次 profileSummary 自动重算
            maybeRefreshProfileSummary(event.getUserId());
        } catch (Exception e) {
            log.warn("偏好更新失败 eventType={} userId={}", event.getEventType(), event.getUserId(), e);
        }
    }

    public void updateFromFingerprint(Long userId, DesignFingerprintDTO fingerprint, double delta) {
        if (userId == null || fingerprint == null) return;
        List<WeightChange> changes = new ArrayList<>();
        if (CollUtil.isNotEmpty(fingerprint.getTone())) {
            for (String tone : fingerprint.getTone()) {
                changes.add(new WeightChange("style", tone.toLowerCase(), delta));
            }
        }
        if (fingerprint.getTokens() != null
                && StrUtil.isNotBlank(fingerprint.getTokens().getColorRatio())) {
            String colorRatio = fingerprint.getTokens().getColorRatio().toLowerCase();
            for (String colorWord : extractColorWords(colorRatio)) {
                changes.add(new WeightChange("color", colorWord, delta));
            }
        }
        if (fingerprint.getLayout() != null
                && StrUtil.isNotBlank(fingerprint.getLayout().getSkeleton())) {
            for (String layoutWord : fingerprint.getLayout().getSkeleton().toLowerCase().split("[→,，、\\-\\s]+")) {
                if (!layoutWord.isBlank()) {
                    changes.add(new WeightChange("layout", layoutWord.trim(), delta));
                }
            }
        }
        if (CollUtil.isNotEmpty(fingerprint.getComponents())) {
            for (String comp : fingerprint.getComponents()) {
                changes.add(new WeightChange("component", comp.toLowerCase(), delta));
            }
        }
        if (fingerprint.getInteraction() != null
                && StrUtil.isNotBlank(fingerprint.getInteraction().getMotion())) {
            changes.add(new WeightChange("interaction",
                    fingerprint.getInteraction().getMotion().toLowerCase(), delta));
        }
        for (WeightChange change : changes) {
            upsertWeight(userId, change.type, change.key, change.delta);
        }
    }

    /** 简单直接更新单个偏好权重（供外部低成本调用，如提示词提取）。 */
    public void updateSingleWeight(Long userId, String type, String key, double delta) {
        upsertWeight(userId, type, key, delta);
    }

    public void handleColorChanged(Long userId, String fromTag, String toTag) {
        if (userId == null) return;
        if (StrUtil.isNotBlank(fromTag)) {
            upsertWeight(userId, "color", fromTag.toLowerCase(), -0.2);
        }
        if (StrUtil.isNotBlank(toTag)) {
            upsertWeight(userId, "color", toTag.toLowerCase(), 0.2);
        }
    }

    public void handleComponentRemoved(Long userId, String componentName) {
        if (userId == null || StrUtil.isBlank(componentName)) return;
        upsertWeight(userId, "component", componentName.toLowerCase(), -0.2);
    }

    public void handleComponentAdded(Long userId, String componentName) {
        if (userId == null || StrUtil.isBlank(componentName)) return;
        upsertWeight(userId, "component", componentName.toLowerCase(), 0.1);
    }

    public void handleLayoutChanged(Long userId, String fromLayout, String toLayout) {
        if (userId == null) return;
        if (StrUtil.isNotBlank(fromLayout)) {
            upsertWeight(userId, "layout", fromLayout.toLowerCase(), -0.1);
        }
        if (StrUtil.isNotBlank(toLayout)) {
            upsertWeight(userId, "layout", toLayout.toLowerCase(), 0.1);
        }
    }

    // ============ 内部逻辑 ============

    private void applyPreferenceUpdate(UserDesignEvent event) {
        String eventType = event.getEventType();
        Long userId = event.getUserId();
        if (userId == null || StrUtil.isBlank(eventType)) {
            return;
        }
        Map<String, Object> metadata = parseMetadata(event.getMetadata());
        switch (eventType) {
            case "page_saved" -> updatePageLifecycleWeight(userId, metadata, "page_saved", 0.1);
            case "page_exported" -> updatePageLifecycleWeight(userId, metadata, "page_exported", 0.2);
            case "page_deleted" -> {
                if (Boolean.TRUE.equals(metadata.get("countAsNegative"))) {
                    updatePageLifecycleWeight(userId, metadata, "page_deleted", -0.1);
                }
            }
            case "page_regenerated" -> updatePageLifecycleWeight(userId, metadata, "page_regenerated", -0.05);
            // ===== 负反馈事件（P0-残留-1 补全） =====
            // 弱负信号：用户撤销修改，仅记录弱惩罚
            case "undo" -> upsertWeight(userId, "style", "undo", -0.05);
            // 强负信号：回退到 AI 原始版本 = 用户否定了自己的所有修改
            case "revert_to_generated" -> upsertWeight(userId, "style", "revert_to_generated", -0.20);
            // 中负信号：中途放弃生成
            case "abandon" -> upsertWeight(userId, "style", "abandon", -0.10);
            // 中负信号：拒绝 AI 推荐
            case "reject_suggestion" -> upsertWeight(userId, "style", "reject_suggestion", -0.10);
            // 强负信号：显式点踩，按 metadata 中 tags 更新（若有）
            case "explicit_dislike" -> {
                if (metadata != null && metadata.containsKey("tags")) {
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) metadata.getOrDefault("tags", Collections.emptyList());
                    for (String tag : tags) {
                        if (StrUtil.isNotBlank(tag)) {
                            upsertWeight(userId, "style", tag.toLowerCase(), -0.20);
                        }
                    }
                } else {
                    upsertWeight(userId, "style", "explicit_dislike", -0.20);
                }
            }
            case "color_changed" -> {
                if (metadata == null) break;
                String from = (String) metadata.get("fromTag");
                String to = (String) metadata.get("toTag");
                handleColorChanged(userId, from, to);
            }
            case "component_removed" -> {
                if (metadata == null) break;
                String comp = (String) metadata.get("componentName");
                handleComponentRemoved(userId, comp);
            }
            case "component_added" -> {
                if (metadata == null) break;
                String comp = (String) metadata.get("componentName");
                handleComponentAdded(userId, comp);
            }
            case "layout_changed" -> {
                if (metadata == null) break;
                String from = (String) metadata.get("from");
                String to = (String) metadata.get("to");
                handleLayoutChanged(userId, from, to);
            }
            default -> log.debug("未注册偏好更新规则的事件类型 eventType={}", eventType);
        }
    }

    /**
     * 页面生命周期事件偏好更新：当 metadata 含 tags 时按 tags 更新（兼容历史格式），
     * 否则使用合成标签 {@code style:<eventType>} 作为弱信号兜底，避免空操作。
     */
    @SuppressWarnings("unchecked")
    private void updatePageLifecycleWeight(Long userId, Map<String, Object> metadata,
                                             String eventType, double delta) {
        List<String> tags = metadata != null
                ? (List<String>) metadata.getOrDefault("tags", Collections.emptyList())
                : Collections.emptyList();
        if (tags.isEmpty()) {
            upsertWeight(userId, "style", eventType, delta);
            return;
        }
        for (String tag : tags) {
            String[] parts = tag.split(":", 2);
            if (parts.length == 2) {
                upsertWeight(userId, parts[0], parts[1].toLowerCase(), delta);
            }
        }
    }

    /**
     * 先原子更新已有行；没有命中时插入。
     * <p>
     * {@code BaseMapper.insert} 会把空的 {@code createTime}/{@code updateTime} 写成 NULL。
     * MariaDB 对 NOT NULL 列会拒绝这个显式 NULL，即使列上有 DEFAULT，插入因此失败。
     * 这里写入当前时间，并用 {@code insertSelective} 跳过仍然为空的列，让数据库默认值生效。
     * 只有唯一键冲突才重试更新；其它插入失败继续抛出，避免调用方把 0 行当成提取成功。
     */
    private void upsertWeight(Long userId, String type, String key, double delta) {
        if (userId == null || StrUtil.isBlank(type) || StrUtil.isBlank(key) || delta == 0d) {
            return;
        }
        if (weightMapper.atomicUpsertWeight(userId, type, key, delta) > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        UserPreferenceWeight newWeight = UserPreferenceWeight.builder()
                .userId(userId)
                .preferenceType(type)
                .preferenceKey(key)
                .weight(delta)
                .evidenceCount(1)
                .lastEvidenceAt(now)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            int inserted = weightMapper.insertSelective(newWeight);
            if (inserted != 1) {
                throw new IllegalStateException("偏好权重插入未写入行 userId=" + userId
                        + " type=" + type + " key=" + key + " inserted=" + inserted);
            }
        } catch (RuntimeException insertFailure) {
            if (!isDuplicateKey(insertFailure)) {
                throw insertFailure;
            }
            int retried = weightMapper.atomicUpsertWeight(userId, type, key, delta);
            if (retried == 0) {
                throw new IllegalStateException("偏好权重并发插入后更新仍未命中 userId=" + userId
                        + " type=" + type + " key=" + key, insertFailure);
            }
            log.debug("偏好权重并发冲突，已重试原子更新 userId={} type={} key={}", userId, type, key);
        }
    }

    private static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1062 || sqlException.getErrorCode() == 1586)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) return Collections.emptyMap();
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.debug("解析 metadata JSON 失败", e);
            return Collections.emptyMap();
        }
    }

    private List<String> extractColorWords(String colorDesc) {
        List<String> result = new ArrayList<>();
        String[] keywords = {"dark", "light", "blue", "red", "green", "orange",
                "purple", "yellow", "pink", "warm", "cool", "neutral", "monochrome",
                "pastel", "vibrant", "muted", "black", "white", "gray", "teal",
                "cyan", "indigo", "amber", "lime", "brown"};
        String lower = colorDesc.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                result.add(kw);
            }
        }
        return result;
    }

    private void addAllTags(List<String> target, List<String> source, String prefix) {
        if (source == null) return;
        for (String s : source) {
            target.add(prefix + ":" + s);
        }
    }

    private record WeightChange(String type, String key, double delta) {}

    // ============ profileSummary 自动刷新 ============

    /**
     * 当 userId 的事件累积达到 SUMMARY_REFRESH_INTERVAL 的整数倍时，
     * 自动从 user_preference_weight 表中取 Top-5 喜欢 + Top-3 反感标签，拼接为自然语言摘要写回 profileSummary。
     * <p>
     * ★ 并发保护（P0-4 修复，2026-07-04）：
     * 刷新动作已异步化（虚拟线程），不阻塞偏好更新主链路。
     * 并发下可能重复触发刷新，但 profileSummary 是幂等覆盖写（取最新权重 Top-N 拼接），
     * 重复刷新最多导致多一次查询和写入，不会造成数据损坏。
     */
    private void maybeRefreshProfileSummary(Long userId) {
        if (userId == null) return;
        try {
            long totalEvents = eventMapper.selectCountByQuery(
                    new QueryWrapper().eq("userId", userId));
            if (totalEvents == 0 || totalEvents % SUMMARY_REFRESH_INTERVAL != 0) return;

            // 异步刷新，不阻塞偏好更新主链路
            preferenceExecutor.execute(() -> {
                try {
                    doRefreshProfileSummary(userId);
                } catch (Exception e) {
                    log.warn("profileSummary 异步刷新失败 userId={}", userId, e);
                }
            });
        } catch (Exception e) {
            log.warn("profileSummary 触发检查失败 userId={}", userId, e);
        }
    }

    /**
     * 执行 profileSummary 刷新（内部方法，已异步化）。
     */
    private void doRefreshProfileSummary(Long userId) {
        try {
        // 查询 Top-5 正权重标签
        QueryWrapper topQuery = new QueryWrapper()
                .eq("userId", userId)
                .orderBy("weight", false)
                .limit(5);
        java.util.List<UserPreferenceWeight> topLikes = weightMapper.selectListByQuery(topQuery);

        // 查询 Top-3 负权重标签
        QueryWrapper bottomQuery = new QueryWrapper()
                .eq("userId", userId)
                .orderBy("weight", true)
                .limit(3);
        java.util.List<UserPreferenceWeight> topDislikes = weightMapper.selectListByQuery(bottomQuery);

        StringBuilder sb = new StringBuilder();
        if (!topLikes.isEmpty()) {
            sb.append("偏爱: ");
            sb.append(topLikes.stream()
                    .map(UserPreferenceWeight::getPreferenceKey)
                    .reduce((a, b) -> a + "、" + b).orElse(""));
        }
        if (!topDislikes.isEmpty() && topDislikes.stream().anyMatch(w -> w.getWeight() < 0)) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("反感: ");
            sb.append(topDislikes.stream()
                    .filter(w -> w.getWeight() < 0)
                    .map(UserPreferenceWeight::getPreferenceKey)
                    .reduce((a, b) -> a + "、" + b).orElse(""));
        }

        try {
            if (!sb.isEmpty()) {
                UserDesignProfile profile = profileMapper.selectOneByQuery(
                        new QueryWrapper().eq("userId", userId));
                if (profile != null) {
                    UserDesignProfile update = new UserDesignProfile();
                    update.setId(profile.getId());
                    update.setProfileSummary(sb.toString());
                    profileMapper.update(update);
                    log.info("profileSummary 已刷新 userId={} summary={}", userId, sb);
                }
            }
        } catch (Exception e) {
            log.warn("profileSummary 刷新失败 userId={}", userId, e);
        }
    } catch (Exception e) {
        log.warn("doRefreshProfileSummary 执行失败 userId={}", userId, e);
    }
    }
}
