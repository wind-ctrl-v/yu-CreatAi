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
     * <p>映射规则（阶段2兜底）：admin → {@link PlanType#ADMIN}，其余登录用户 → {@link PlanType#PRO}，
     * 未登录/空 → {@link PlanType#FREE}。</p>
     */
    public PlanType getPlan(User loginUser) {
        if (loginUser == null) {
            return PlanType.FREE;
        }
        if (ROLE_ADMIN.equalsIgnoreCase(loginUser.getUserRole())) {
            return PlanType.ADMIN;
        }
        // TODO 阶段5：接入真实套餐表（user_plan），区分 FREE/PRO/PREMIUM/ENTERPRISE
        return PlanType.PRO;
    }
}
