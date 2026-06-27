package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.PlanType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户权益（套餐）服务。
 * <p><b>当前为绿地实现</b>：{@code User} 实体尚无套餐字段，阶段2先按 {@code userRole} 兜底映射，
 * 后续接入真实套餐表时仅需替换 {@link #getPlan(User)} 实现，上层无需改动。</p>
 */
@Slf4j
@Service
public class UserEntitlementService {

    private static final String ROLE_ADMIN = "admin";

    /**
     * 获取用户套餐。
     * <p>优先读 {@link User#getPlanType()} 字段（真实套餐），没有或无法识别时回退为角色映射：
     * admin → {@link PlanType#ADMIN}，其余登录用户 → {@link PlanType#PRO}。
     * 未登录/空 → {@link PlanType#FREE}。</p>
     */
    public PlanType getPlan(User loginUser) {
        if (loginUser == null) {
            return PlanType.FREE;
        }
        // 优先从真实 planType 字段读取
        String planType = loginUser.getPlanType();
        if (planType != null && !planType.isBlank()) {
            PlanType resolved = PlanType.fromName(planType);
            if (resolved != PlanType.FREE || "FREE".equalsIgnoreCase(planType)) {
                // fromName 对未知值会兜底返回 FREE，但若原值就是 "FREE" 则正确
                // 对 PRO/PREMIUM/ENTERPRISE/ADMIN 会正确匹配
                return resolved;
            }
        }
        // 回退：无 planType 或无法识别时按角色兜底
        if (ROLE_ADMIN.equalsIgnoreCase(loginUser.getUserRole())) {
            return PlanType.ADMIN;
        }
        return PlanType.PRO;
    }
}
