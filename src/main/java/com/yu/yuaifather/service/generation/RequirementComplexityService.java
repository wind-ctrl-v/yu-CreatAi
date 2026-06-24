package com.yu.yuaifather.service.generation;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.dto.generation.RequirementComplexity;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 需求复杂度分析服务（启发式）。
 * <p>基于用户消息关键词 + 代码生成类型粗略判断复杂度，用于在套餐允许的上限内动态升降级模式。
 * 纯本地规则，不消费 LLM Token。</p>
 */
@Slf4j
@Service
public class RequirementComplexityService {

    /** 简单修改类关键词。 */
    private static final String[] SIMPLE_KEYWORDS = {
            "改颜色", "改个颜色", "改背景", "改标题", "改文字", "改文案", "换图", "换颜色",
            "调整间距", "改字体", "改大小", "微调", "改按钮", "修改一下", "改一下"
    };

    /** 复杂需求类关键词。 */
    private static final String[] COMPLEX_KEYWORDS = {
            "完整项目", "多页面", "多个页面", "响应式", "动效", "动画", "整站", "官网",
            "后台管理", "仪表盘", "dashboard", "saas", "电商", "复杂", "高端", "重新生成整个"
    };

    /**
     * 分析需求复杂度。
     *
     * @param message     用户消息
     * @param codeGenType 代码生成类型
     * @return 复杂度
     */
    public RequirementComplexity analyze(String message, CodeGenTypeEnum codeGenType) {
        String text = message == null ? "" : message.toLowerCase();

        // 简单修改优先判定（短消息 + 命中简单关键词）
        if (StrUtil.isNotBlank(text)) {
            for (String kw : SIMPLE_KEYWORDS) {
                if (text.contains(kw.toLowerCase())) {
                    return RequirementComplexity.SIMPLE;
                }
            }
            for (String kw : COMPLEX_KEYWORDS) {
                if (text.contains(kw.toLowerCase())) {
                    return RequirementComplexity.COMPLEX;
                }
            }
        }

        // Vue 工程默认偏复杂，其余默认普通
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            return RequirementComplexity.NORMAL;
        }
        return RequirementComplexity.NORMAL;
    }
}
