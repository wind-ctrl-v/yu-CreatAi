package com.yu.yuaifather.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从用户提示词/修改指令中通过 LLM 提取偏好标签（统一偏好提取入口）。
 * <p>
 * ★ 统一偏好提取方案（2026-07-04 重构）：取代原先的"前端规则 + 后端 LLM"双路径，
 * 全部走 LLM 提取，成本极低（每次约 200 token），粒度更细，且支持正负向偏好。
 * <p>
 * 异步执行，不阻塞主流程。
 */
@Slf4j
@Component
public class PromptPreferenceExtractor {

    /** 正向偏好权重 delta */
    private static final double POSITIVE_DELTA = 0.15;
    /** 负向偏好权重 delta（绝对值） */
    private static final double NEGATIVE_DELTA = -0.15;

    @Resource
    private ChatModel chatModel;

    @Resource
    private com.yu.yuaifather.worker.PreferenceUpdateWorker preferenceUpdateWorker;

    /** AiServices 代理接口 */
    interface PromptPreferenceAi {
        @SystemMessage("""
                你是一个设计偏好提取器。从用户的设计修改指令或创建应用的需求文本中提取偏好标签。

                提取规则：
                1. 区分「想要的」（正向偏好）和「不想要的/要替换掉的」（负向偏好）
                   - "改成蓝色" → 正向 colors: ["蓝色"]
                   - "去掉渐变" → 负向 styles: ["渐变"]
                   - "把蓝色改成绿色" → 正向 colors: ["绿色"], 负向 colors: ["蓝色"]
                2. 标签简洁（1-8个汉字或英文单词），统一用中文
                3. 只提取明确表达的偏好，不要推断
                4. 多意图指令（如"风格改清爽，同时按钮改蓝色"）要全部提取

                输出严格 JSON（无 markdown 围栏）：
                {
                  "positive": {
                    "colors": ["蓝色"],
                    "styles": ["清爽", "极简"],
                    "layouts": [],
                    "components": [],
                    "interactions": []
                  },
                  "negative": {
                    "colors": [],
                    "styles": [],
                    "layouts": [],
                    "components": [],
                    "interactions": []
                  }
                }
                如果没有对应类别的内容，数组为空 []。只输出 JSON，不要任何解释。
                """)
        String extract(String prompt);
    }

    private final PromptPreferenceAi ai;

    public PromptPreferenceExtractor(ChatModel chatModel) {
        this.ai = AiServices.create(PromptPreferenceAi.class, chatModel);
    }

    /**
     * 从提示词/修改指令中异步提取偏好标签并更新权重表（统一入口）。
     * <p>
     * 正向偏好 +0.15，负向偏好 -0.15，同一标签在一次提取中去重。
     *
     * @param userId    用户ID
     * @param prompt    用户输入的提示词或修改指令文本
     */
    public void extractAndRecord(Long userId, String prompt) {
        extractAndRecordInternal(userId, prompt, false);
    }

    /**
     * 从提示词中提取偏好标签，全部以负向权重更新（用于"用户删除应用"等负反馈场景）。
     * <p>
     * 删除应用 = 用户对该设计方向不满意 → 从原始需求文本中提取的所有标签全部以 -0.15 更新。
     *
     * @param userId    用户ID
     * @param prompt    应用创建时的原始需求文本
     */
    public void extractAndRecordNegative(Long userId, String prompt) {
        extractAndRecordInternal(userId, prompt, true);
    }

    /**
     * 偏好提取内部实现。
     *
     * @param userId         用户ID
     * @param prompt         输入文本
     * @param forceNegative  是否强制全部以负向权重更新（删除场景）
     */
    private void extractAndRecordInternal(Long userId, String prompt, boolean forceNegative) {
        if (userId == null || prompt == null || prompt.isBlank()) return;

        try {
            String response = ai.extract("用户提示词：" + prompt);
            JSONObject json = JSONUtil.parseObj(cleanJson(response));

            Set<String> updatedKeys = new LinkedHashSet<>();
            int positiveCount = 0;
            int negativeCount = 0;

            JSONObject positive = json.getJSONObject("positive");
            JSONObject negative = json.getJSONObject("negative");

            if (positive != null) {
                double posDelta = forceNegative ? NEGATIVE_DELTA : POSITIVE_DELTA;
                positiveCount += recordGroup(userId, "color", positive.getJSONArray("colors"), posDelta, updatedKeys);
                positiveCount += recordGroup(userId, "style", positive.getJSONArray("styles"), posDelta, updatedKeys);
                positiveCount += recordGroup(userId, "layout", positive.getJSONArray("layouts"), posDelta, updatedKeys);
                positiveCount += recordGroup(userId, "component", positive.getJSONArray("components"), posDelta, updatedKeys);
                positiveCount += recordGroup(userId, "interaction", positive.getJSONArray("interactions"), posDelta, updatedKeys);
            }

            if (negative != null) {
                // 负向标签始终以负向 delta 更新（无论 forceNegative）
                negativeCount += recordGroup(userId, "color", negative.getJSONArray("colors"), NEGATIVE_DELTA, updatedKeys);
                negativeCount += recordGroup(userId, "style", negative.getJSONArray("styles"), NEGATIVE_DELTA, updatedKeys);
                negativeCount += recordGroup(userId, "layout", negative.getJSONArray("layouts"), NEGATIVE_DELTA, updatedKeys);
                negativeCount += recordGroup(userId, "component", negative.getJSONArray("components"), NEGATIVE_DELTA, updatedKeys);
                negativeCount += recordGroup(userId, "interaction", negative.getJSONArray("interactions"), NEGATIVE_DELTA, updatedKeys);
            }

            String mode = forceNegative ? "（负反馈模式）" : "";
            log.info("偏好提取完成{} userId={} positive={} negative={}", mode, userId, positiveCount, negativeCount);
        } catch (Exception e) {
            log.warn("偏好提取失败 userId={}", userId, e);
        }
    }

    /**
     * 提取一组标签并更新权重，同一 type:key 在本次提取中只更新一次。
     *
     * @param userId       用户ID
     * @param type         偏好类型（color/style/layout/component/interaction）
     * @param jsonArray    LLM 输出的标签数组
     * @param delta        权重增量（正向 +0.15，负向 -0.15）
     * @param updatedKeys  本次提取已更新的 type:key 集合（避免正负向重复更新同一标签）
     * @return 本次实际更新的标签数
     */
    private int recordGroup(Long userId, String type, JSONArray jsonArray, double delta, Set<String> updatedKeys) {
        if (jsonArray == null || jsonArray.isEmpty()) return 0;
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (int i = 0; i < jsonArray.size(); i++) {
            String tag = jsonArray.getStr(i);
            if (tag == null || tag.isBlank()) continue;
            String normalizedTag = tag.trim().toLowerCase();
            String dedupKey = type + ":" + normalizedTag;
            if (!seen.add(normalizedTag)) continue;
            if (!updatedKeys.add(dedupKey)) continue;
            preferenceUpdateWorker.updateSingleWeight(userId, type, normalizedTag, delta);
            count++;
        }
        return count;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return s;
    }
}
