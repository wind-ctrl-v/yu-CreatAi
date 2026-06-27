package com.yu.yuaifather.service;

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
 * 偏好更新服务（阶段一：用户偏好闭环）。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.worker.PreferenceUpdateWorker}，请使用 PreferenceUpdateWorker 替代。
 */
@Deprecated
@Slf4j
@Service
public class PreferenceUpdateService {

    private final UserDesignEventMapper eventMapper;
    private final UserPreferenceWeightMapper weightMapper;
    private final ObjectMapper objectMapper;

    public PreferenceUpdateService(UserDesignEventMapper eventMapper,
                                   UserPreferenceWeightMapper weightMapper,
                                   ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.weightMapper = weightMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录用户行为事件并更新偏好。
     *
     * @param event 用户设计行为事件
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordAndUpdate(UserDesignEvent event) {
        // 1. 落库事件
        eventMapper.insert(event);

        // 2. 根据事件类型更新偏好权重
        try {
            applyPreferenceUpdate(event);
        } catch (Exception e) {
            log.warn("偏好更新失败 eventType={} userId={}", event.getEventType(), event.getUserId(), e);
        }
    }

    /**
     * 根据保存/导出的指纹更新偏好权重。
     * <p>
     * 从指纹的六层结构中提取 style/color/layout/component/interaction 标签，
     * 批量更新权重。
     *
     * @param userId       用户ID
     * @param fingerprint  六层指纹DTO
     * @param delta        权重变化量（正=喜欢，负=反感）
     */
    public void updateFromFingerprint(Long userId, DesignFingerprintDTO fingerprint, double delta) {
        if (userId == null || fingerprint == null) {
            return;
        }

        List<WeightChange> changes = new ArrayList<>();

        // 提取色调标签（style + tone）
        if (CollUtil.isNotEmpty(fingerprint.getTone())) {
            for (String tone : fingerprint.getTone()) {
                changes.add(new WeightChange("style", tone.toLowerCase(), delta));
            }
        }

        // 提取颜色（从 tokens.colorRatio）
        if (fingerprint.getTokens() != null
                && StrUtil.isNotBlank(fingerprint.getTokens().getColorRatio())) {
            String colorRatio = fingerprint.getTokens().getColorRatio().toLowerCase();
            for (String colorWord : extractColorWords(colorRatio)) {
                changes.add(new WeightChange("color", colorWord, delta));
            }
        }

        // 提取布局
        if (fingerprint.getLayout() != null
                && StrUtil.isNotBlank(fingerprint.getLayout().getSkeleton())) {
            for (String layoutWord : fingerprint.getLayout().getSkeleton().toLowerCase().split("[→,，、\\-\\s]+")) {
                if (!layoutWord.isBlank()) {
                    changes.add(new WeightChange("layout", layoutWord.trim(), delta));
                }
            }
        }

        // 提取组件
        if (CollUtil.isNotEmpty(fingerprint.getComponents())) {
            for (String comp : fingerprint.getComponents()) {
                changes.add(new WeightChange("component", comp.toLowerCase(), delta));
            }
        }

        // 提取交互
        if (fingerprint.getInteraction() != null
                && StrUtil.isNotBlank(fingerprint.getInteraction().getMotion())) {
            changes.add(new WeightChange("interaction",
                    fingerprint.getInteraction().getMotion().toLowerCase(), delta));
        }

        // 批量更新
        for (WeightChange change : changes) {
            upsertWeight(userId, change.type, change.key, change.delta);
        }
    }

    /**
     * 处理颜色变更事件（from_tag → to_tag）。
     */
    public void handleColorChanged(Long userId, String fromTag, String toTag) {
        if (userId == null) return;
        if (StrUtil.isNotBlank(fromTag)) {
            upsertWeight(userId, "color", fromTag.toLowerCase(), -0.2);
        }
        if (StrUtil.isNotBlank(toTag)) {
            upsertWeight(userId, "color", toTag.toLowerCase(), 0.2);
        }
    }

    /**
     * 处理组件移除事件。
     */
    public void handleComponentRemoved(Long userId, String componentName) {
        if (userId == null || StrUtil.isBlank(componentName)) return;
        upsertWeight(userId, "component", componentName.toLowerCase(), -0.2);
    }

    /**
     * 处理组件添加事件。
     */
    public void handleComponentAdded(Long userId, String componentName) {
        if (userId == null || StrUtil.isBlank(componentName)) return;
        upsertWeight(userId, "component", componentName.toLowerCase(), 0.1);
    }

    /**
     * 处理布局变更事件。
     */
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

    /**
     * 根据事件类型应用偏好更新规则。
     */
    private void applyPreferenceUpdate(UserDesignEvent event) {
        String eventType = event.getEventType();
        Long userId = event.getUserId();

        if (userId == null || StrUtil.isBlank(eventType)) {
            return;
        }

        // 根据事件 metadata 提取指纹或标签信息
        Map<String, Object> metadata = parseMetadata(event.getMetadata());
        if (metadata == null) {
            log.debug("事件 metadata 为空，跳过偏好更新 eventType={}", eventType);
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
        // 尝试从 metadata 提取标签
        List<String> tags = (List<String>) metadata.getOrDefault("tags", Collections.emptyList());
        if (tags.isEmpty()) {
            // 兼容旧格式：styleTags / colorTags / componentTags
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

    /**
     * 原子更新权重：先尝试 UPDATE weight = weight + delta，0 行时 INSERT。
     * <p>
     * 利用 MySQL 行锁 + UNIQUE 约束保证并发安全：
     * <ol>
     *   <li>UPDATE 在匹配行上加行锁，原子地完成 weight + delta；</li>
     *   <li>若记录不存在（affected = 0），INSERT 新行；</li>
     *   <li>UNIQUE (userId, preferenceType, preferenceKey) 防止并发 INSERT 重复。</li>
     * </ol>
     */
    private void upsertWeight(Long userId, String type, String key, double delta) {
        if (delta == 0d) return;

        // 原子 UPDATE：利用行锁，无需先查
        int affected = weightMapper.atomicUpsertWeight(userId, type, key, delta);

        if (affected == 0) {
            // 记录不存在，尝试 INSERT（与其他并发线程竞争时 UNIQUE 约束可兜底）
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
                // 并发 INSERT 冲突：重试一次原子 UPDATE（另一个线程已创建记录）
                log.debug("偏好权重并发冲突，重试原子更新 userId={} type={} key={}", userId, type, key);
                weightMapper.atomicUpsertWeight(userId, type, key, delta);
            }
        }
        log.debug("偏好权重更新 userId={} type={} key={} delta={}", userId, type, key, delta);
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

    /**
     * 从颜色描述中提取颜色词汇。
     */
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

    /**
     * 权重变更内部记录。
     */
    private record WeightChange(String type, String key, double delta) {}
}
