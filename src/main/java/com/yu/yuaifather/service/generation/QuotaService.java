package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.GenerationMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 额度服务。
 * <p><b>当前为绿地实现（阶段2 no-op）</b>：直接返回请求模式，不做额度限制。
 * 阶段5接入真实额度（{@code user_quota} 表）后，{@link #applyQuotaLimit} 将在额度不足时
 * 自动降级：high_quality → standard → low_cost。</p>
 */
@Slf4j
@Service
public class QuotaService {

    /**
     * 按额度对目标模式做限制（不足则降级）。阶段2恒返回入参模式。
     *
     * @param loginUser 当前用户
     * @param mode      期望模式
     * @return 实际可用模式
     */
    public GenerationMode applyQuotaLimit(User loginUser, GenerationMode mode) {
        // TODO 阶段5：查询当日额度，不足时降级并返回降级后的模式
        return mode;
    }
}
