package com.yu.yuaifather.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.yuaifather.model.dto.agent.UserDesignContext;
import com.yu.yuaifather.mapper.UserDesignProfileMapper;
import com.yu.yuaifather.mapper.UserPreferenceWeightMapper;
import com.yu.yuaifather.model.entity.UserDesignProfile;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好服务（阶段一：用户偏好闭环）。
 * <p>
 * 提供用户设计画像和偏好权重的加载、组装功能。
 * <p>
 * ★ 时间衰减（P0-1 修复，2026-07-04）：读取权重时按 {@code lastEvidenceAt} 计算指数衰减，
 * 半衰期可配置（{@code agent.preference.half-life-days}，默认 30 天）。
 * 旧偏好的有效权重随时间递减，避免一年前的高权重压制当前新偏好。
 * <p>
 * 性能目标：加载耗时 < 50ms（2 次主键查询，无 JOIN）。
 *
 * @author yu
 */
@Slf4j
@Service
public class UserPreferenceService {

    /** 半衰期（天），超过此时间权衰减为原来的一半。 */
    @Value("${agent.preference.half-life-days:30}")
    private double halfLifeDays;

    /** 衰减下限：有效权重低于原始权重的此比例时，视为可忽略（避免极小浮点数干扰）。 */
    private static final double DECAY_FLOOR_RATIO = 0.05;

    private final UserDesignProfileMapper profileMapper;
    private final UserPreferenceWeightMapper weightMapper;
    private final ObjectMapper objectMapper;

    public UserPreferenceService(UserDesignProfileMapper profileMapper,
                                 UserPreferenceWeightMapper weightMapper,
                                 ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.weightMapper = weightMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载用户设计上下文（画像 + 权重）。
     *
     * @param userId 用户ID
     * @return 用户设计上下文，无偏好数据时返回空上下文
     */
    public UserDesignContext loadUserDesignContext(Long userId) {
        if (userId == null) {
            return UserDesignContext.builder().userId(null).build();
        }

        UserDesignContext.UserDesignContextBuilder builder = UserDesignContext.builder().userId(userId);

        // 1. 加载用户设计画像（按 userId 查询，非主键 id）
        UserDesignProfile profile = profileMapper.selectOneByMap(
                Collections.singletonMap("userId", userId));
        if (profile != null) {
            builder.profileSummary(profile.getProfileSummary());
            builder.preferredStyles(parseJsonList(profile.getPreferredStyles()));
            builder.dislikedStyles(parseJsonList(profile.getDislikedStyles()));
            builder.preferredColors(parseJsonList(profile.getPreferredColors()));
            builder.dislikedColors(parseJsonList(profile.getDislikedColors()));
            builder.preferredLayouts(parseJsonList(profile.getPreferredLayouts()));
            builder.dislikedLayouts(parseJsonList(profile.getDislikedLayouts()));
            builder.preferredComponents(parseJsonList(profile.getPreferredComponents()));
            builder.dislikedComponents(parseJsonList(profile.getDislikedComponents()));
            builder.preferredInteractions(parseJsonList(profile.getPreferredInteractions()));
            builder.dislikedInteractions(parseJsonList(profile.getDislikedInteractions()));
        }

        // 2. 加载偏好权重（应用时间衰减）
        List<UserPreferenceWeight> weights = weightMapper.selectListByMap(
                Collections.singletonMap("userId", userId));
        if (CollUtil.isNotEmpty(weights)) {
            Map<String, Double> weightMap = new HashMap<>();
            for (UserPreferenceWeight w : weights) {
                String key = w.getPreferenceType() + ":" + w.getPreferenceKey();
                weightMap.put(key, effectiveWeight(w));
            }
            builder.preferenceWeights(weightMap);
        }

        return builder.build();
    }

    /**
     * 加载用户偏好权重列表。
     */
    public List<UserPreferenceWeight> loadPreferenceWeights(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return weightMapper.selectListByMap(Collections.singletonMap("userId", userId));
    }

    /**
     * 计算给定标签列表的用户偏好得分。
     * <p>
     * 得分 = 命中标签的<b>有效</b>权重之和（应用时间衰减）。用于个性化 rerank。
     *
     * @param userId  用户ID
     * @param type    偏好类型（style/color/layout/component/interaction）
     * @param keys    待匹配的标签列表
     * @return 偏好得分，正值为喜欢，负值为反感
     */
    public double calculatePreferenceScore(Long userId, String type, List<String> keys) {
        if (userId == null || keys == null || keys.isEmpty()) {
            return 0d;
        }
        List<UserPreferenceWeight> weights = loadPreferenceWeights(userId);
        if (CollUtil.isEmpty(weights)) {
            return 0d;
        }

        double score = 0d;
        for (String key : keys) {
            for (UserPreferenceWeight w : weights) {
                if (type.equals(w.getPreferenceType())
                        && key.equalsIgnoreCase(w.getPreferenceKey())) {
                    score += effectiveWeight(w);
                }
            }
        }
        return score;
    }

    /**
     * 计算用户反感惩罚分。
     * <p>
     * 惩罚 = 命中的<b>有效</b>负向权重绝对值之和。用于个性化 rerank 降权。
     */
    public double calculateDislikePenalty(Long userId, String type, List<String> keys) {
        if (userId == null || keys == null || keys.isEmpty()) {
            return 0d;
        }
        List<UserPreferenceWeight> weights = loadPreferenceWeights(userId);
        if (CollUtil.isEmpty(weights)) {
            return 0d;
        }

        double penalty = 0d;
        for (String key : keys) {
            for (UserPreferenceWeight w : weights) {
                if (type.equals(w.getPreferenceType())
                        && key.equalsIgnoreCase(w.getPreferenceKey())
                        && w.getWeight() < 0) {
                    penalty += Math.abs(effectiveWeight(w));
                }
            }
        }
        return penalty;
    }

    /**
     * 计算权重的有效值：应用指数时间衰减。
     * <p>
     * 公式：{@code effective = raw * exp(-Δday / halfLife * ln2)}
     * <ul>
     *   <li>半衰期内（Δ < halfLife）权重保持较高</li>
     *   <li>超过半衰期权重减半，两个半衰期减为 1/4，以此类推</li>
     *   <li>衰减低于 {@link #DECAY_FLOOR_RATIO} 时返回 0（视为可忽略）</li>
     *   <li>{@code lastEvidenceAt} 为 null 时不衰减（历史数据兼容）</li>
     * </ul>
     */
    private double effectiveWeight(UserPreferenceWeight w) {
        double raw = w.getWeight() == null ? 0d : w.getWeight();
        if (raw == 0d) return 0d;
        if (w.getLastEvidenceAt() == null) return raw;  // 无时间记录，不衰减

        long daysSince = Duration.between(w.getLastEvidenceAt(), LocalDateTime.now()).toDays();
        if (daysSince <= 0) return raw;  // 当天证据，不衰减

        double decayFactor = Math.exp(-daysSince / halfLifeDays * Math.log(2));
        if (decayFactor < DECAY_FLOOR_RATIO) return 0d;
        return raw * decayFactor;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.debug("解析 JSON 列表失败 json={}", json, e);
            return Collections.emptyList();
        }
    }
}
