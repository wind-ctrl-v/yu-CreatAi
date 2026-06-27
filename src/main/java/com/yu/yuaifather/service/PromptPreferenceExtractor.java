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
 * 从用户提示词中通过 LLM 提取偏好标签。
 * <p>异步执行，不阻塞创建应用主流程。</p>
 */
@Slf4j
@Component
public class PromptPreferenceExtractor {

    @Resource
    private ChatModel chatModel;

    @Resource
    private com.yu.yuaifather.worker.PreferenceUpdateWorker preferenceUpdateWorker;

    /** AiServices 代理接口 */
    interface PromptPreferenceAi {
        @SystemMessage("""
                你是一个设计偏好提取器。从用户创建应用的提示词中提取设计偏好标签。
                只提取明确表达的偏好，不要推断。标签必须简洁（1-8个汉字）。

                输出严格 JSON（无 markdown围栏）：
                {
                  "colors": ["浅绿色"],
                  "styles": ["简约", "科技感"],
                  "layouts": ["居中布局"],
                  "components": ["轮播"],
                  "interactions": ["动态"],
                  "applicationType": "博客"
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
     * 从提示词中异步提取偏好标签并更新权重表。
     */
    public void extractAndRecord(Long userId, String prompt) {
        if (userId == null || prompt == null || prompt.isBlank()) return;

        try {
            String response = ai.extract("用户提示词：" + prompt);
            JSONObject json = JSONUtil.parseObj(cleanJson(response));

            int count = 0;
            count += recordGroup(userId, "color", json.getJSONArray("colors"));
            count += recordGroup(userId, "style", json.getJSONArray("styles"));
            count += recordGroup(userId, "layout", json.getJSONArray("layouts"));
            count += recordGroup(userId, "component", json.getJSONArray("components"));
            count += recordGroup(userId, "interaction", json.getJSONArray("interactions"));

            log.info("提示词偏好提取完成 userId={} tags={}", userId, count);
        } catch (Exception e) {
            log.warn("提示词偏好提取失败 userId={}", userId, e);
        }
    }

    private int recordGroup(Long userId, String type, JSONArray jsonArray) {
        if (jsonArray == null || jsonArray.isEmpty()) return 0;
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (int i = 0; i < jsonArray.size(); i++) {
            String tag = jsonArray.getStr(i);
            if (tag == null || tag.isBlank() || !seen.add(tag)) continue;
            preferenceUpdateWorker.updateSingleWeight(userId, type, tag.trim(), 0.15);
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
