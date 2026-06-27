package com.yu.yuaifather.service.generation;

import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.config.GenerationProperties;
import com.yu.yuaifather.mapper.UserQuotaMapper;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.entity.UserQuota;
import com.yu.yuaifather.model.enums.GenerationMode;
import com.yu.yuaifather.model.enums.PlanType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 额度服务：每日按模式限制生成次数，不足时自动降级。
 * <p>降级顺序：high_quality → standard → low_cost。</p>
 */
@Slf4j
@Service
public class QuotaService {

    private final UserQuotaMapper userQuotaMapper;
    private final GenerationProperties properties;
    private final UserEntitlementService entitlementService;

    public QuotaService(UserQuotaMapper userQuotaMapper,
                        GenerationProperties properties,
                        UserEntitlementService entitlementService) {
        this.userQuotaMapper = userQuotaMapper;
        this.properties = properties;
        this.entitlementService = entitlementService;
    }

    /**
     * 按额度对目标模式做限制（不足则逐级降级）。
     *
     * @param loginUser 当前用户
     * @param mode      期望模式（已经被套餐封顶）
     * @return 实际可用模式
     */
    public GenerationMode applyQuotaLimit(User loginUser, GenerationMode mode) {
        if (loginUser == null || mode == null) {
            return mode;
        }
        PlanType plan = entitlementService.getPlan(loginUser);
        // 管理员不受额度限制
        if (plan == PlanType.ADMIN) {
            return mode;
        }

        GenerationMode candidate = mode;
        // 逐级降级：从最高试到最低
        while (candidate != null) {
            int limit = getDailyLimit(plan, candidate);
            if (limit <= 0) {
                // 无限制
                return candidate;
            }
            int used = getTodayUsed(loginUser.getId(), candidate);
            if (used < limit) {
                return candidate;
            }
            // 当前模式额度已满，降一级
            candidate = downgrade(candidate);
        }
        // 所有模式都满了，降到 low_cost
        log.warn("用户 {} 所有模式今日额度已用完，降至 low_cost", loginUser.getId());
        return GenerationMode.LOW_COST;
    }

    /**
     * 记录一次生成消耗（事务中 upsert）。
     */
    @Transactional
    public void recordUsage(User loginUser, GenerationMode mode) {
        if (loginUser == null || mode == null) return;
        Long userId = loginUser.getId();
        String modeValue = mode.getValue();
        LocalDate today = LocalDate.now();

        UserQuota existing = userQuotaMapper.selectOneByQuery(
                new QueryWrapper()
                        .eq("user_id", userId)
                        .eq("quota_date", today)
                        .eq("generation_mode", modeValue)
        );

        if (existing != null) {
            UserQuota update = new UserQuota();
            update.setId(existing.getId());
            update.setUsedCount(existing.getUsedCount() + 1);
            userQuotaMapper.update(update);
        } else {
            UserQuota record = UserQuota.builder()
                    .userId(userId)
                    .quotaDate(today)
                    .generationMode(modeValue)
                    .usedCount(1)
                    .build();
            userQuotaMapper.insert(record);
        }
    }

    private int getTodayUsed(Long userId, GenerationMode mode) {
        UserQuota row = userQuotaMapper.selectOneByQuery(
                new QueryWrapper()
                        .eq("user_id", userId)
                        .eq("quota_date", LocalDate.now())
                        .eq("generation_mode", mode.getValue())
        );
        return row != null ? row.getUsedCount() : 0;
    }

    private int getDailyLimit(PlanType plan, GenerationMode mode) {
        GenerationProperties.EntitlementConfig cfg = properties.getEntitlementOrDefault(plan.name());
        return switch (mode) {
            case LOW_COST -> cfg.getDailyLowCostLimit() != null ? cfg.getDailyLowCostLimit() : -1;
            case STANDARD -> cfg.getDailyStandardLimit() != null ? cfg.getDailyStandardLimit() : -1;
            case HIGH_QUALITY -> cfg.getDailyHighQualityLimit() != null ? cfg.getDailyHighQualityLimit() : -1;
        };
    }

    private GenerationMode downgrade(GenerationMode mode) {
        return switch (mode) {
            case HIGH_QUALITY -> GenerationMode.STANDARD;
            case STANDARD -> GenerationMode.LOW_COST;
            case LOW_COST -> null;
        };
    }
}
