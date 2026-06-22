package com.yu.yuaifather.worker;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.mapper.UserDesignEventMapper;
import com.yu.yuaifather.mapper.UserPreferenceWeightMapper;
import com.yu.yuaifather.model.entity.UserDesignEvent;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * PreferenceUpdateWorker：偏好更新异步 Worker。
 * <p>
 * 从 PreferenceUpdateService 改名重构而来。
 * <p>
 * 基于用户行为事件的规则驱动偏好更新：
 * <ul>
 *   <li>正反馈：page_saved(+0.1), page_exported(+0.2), user_rating_5(+0.2)</li>
 *   <li>负反馈：page_deleted(-0.1), page_regenerated(-0.05), user_rating_1_2(-0.2)</li>
 *   <li>显式编辑：color_changed(from→to: -0.2/+0.2), component_removed(-0.2), component_added(+0.1)</li>
 * </ul>
 */
@Slf4j
@Service
public class PreferenceUpdateWorker {

    private final UserDesignEventMapper eventMapper;
    private final UserPreferenceWeightMapper weightMapper;
    private final ObjectMapper objectMapper;

    public PreferenceUpdateWorker(UserDesignEventMapper eventMapper,
                                   UserPreferenceWeightMapper weightMapper,
                                   ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.weightMapper = weightMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordAndUpdate(UserDesignEvent event) {
        eventMapper.insert(event);
        try {
            applyPreferenceUpdate(event);
        } catch (Exception e) {
            log.warn("偏好更新失败 eventType={} userId={}", event.getEventType(), event.getUserId(), e);
        }
    }

    public void updateFromFingerprint(Long userId, DesignFingerprintDTO fingerprint, double delta) {
        if (userId == null || fingerprint == null) {
            return;
        }
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
        if (metadata == null) {
            return;
        }
        switch (eventType) {
            case "page_saved" -> updateFromMetadata(userId, metadata, 0.1);
            case "page_exported" -> updateFromMetadata(userId, metadata, 0.2);
            case "page_deleted" -> updateFromMetadata(userId, metadata, -0.1);
            case "page_regenerated" -> updateFromMetadata(userId, metadata, -0.05);
            case "color_changed" -> {
                String from = (String) metadata.get("fromTag");
                String to = (String) metadata.get("toTag");
                handleColorChanged(userId, from, to);
            }
            case "component_removed" -> {
                String comp = (String) metadata.get("componentName");
                handleComponentRemoved(userId, comp);
            }
            case "component_added" -> {
                String comp = (String) metadata.get("componentName");
                handleComponentAdded(userId, comp);
            }
            case "layout_changed" -> {
                String from = (String) metadata.get("from");
                String to = (String) metadata.get("to");
                handleLayoutChanged(userId, from, to);
            }
            default -> log.debug("未注册偏好更新规则的事件类型 eventType={}", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private void updateFromMetadata(Long userId, Map<String, Object> metadata, double delta) {
        List<String> tags = (List<String>) metadata.getOrDefault("tags", Collections.emptyList());
        if (tags.isEmpty()) {
            tags = new ArrayList<>();
            addAllTags(tags, (List<String>) metadata.get("styleTags"), "style");
            addAllTags(tags, (List<String>) metadata.get("colorTags"), "color");
            addAllTags(tags, (List<String>) metadata.get("componentTags"), "component");
        }
        if (tags.isEmpty()) return;
        for (String tag : tags) {
            String[] parts = tag.split(":", 2);
            if (parts.length == 2) {
                upsertWeight(userId, parts[0], parts[1].toLowerCase(), delta);
            }
        }
    }

    private void upsertWeight(Long userId, String type, String key, double delta) {
        if (delta == 0d) return;
        int affected = weightMapper.atomicUpsertWeight(userId, type, key, delta);
        if (affected == 0) {
            try {
                UserPreferenceWeight newWeight = UserPreferenceWeight.builder()
                        .userId(userId)
                        .preferenceType(type)
                        .preferenceKey(key)
                        .weight(delta)
                        .evidenceCount(1)
                        .lastEvidenceAt(LocalDateTime.now())
                        .build();
                weightMapper.insert(newWeight);
            } catch (Exception dupEx) {
                log.debug("偏好权重并发冲突，重试原子更新 userId={} type={} key={}", userId, type, key);
                weightMapper.atomicUpsertWeight(userId, type, key, delta);
            }
        }
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
}
