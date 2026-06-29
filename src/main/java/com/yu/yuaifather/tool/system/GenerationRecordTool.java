package com.yu.yuaifather.tool.system;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.mapper.GeneratedPageMapper;
import com.yu.yuaifather.model.entity.GeneratedPage;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * GenerationRecordTool：记录页面生成结果到 generated_page 表。
 * <p>
 * 每次 AI 代码生成完成后调用，持久化产物快照（html/css/js）、需求文本、
 * 设计规范快照和 RAG 证据快照，支持后续质量评估和用户指纹反哺。
 */
@Slf4j
@Component
public class GenerationRecordTool implements SystemTool<GenerationRecordTool.RecordInput, Long> {

    public static final String TOOL_NAME = "GenerationRecordTool";

    private final GeneratedPageMapper generatedPageMapper;

    public GenerationRecordTool(GeneratedPageMapper generatedPageMapper) {
        this.generatedPageMapper = generatedPageMapper;
    }

    public record RecordInput(
            Long requestId,
            Long userId,
            Long projectId,
            String generationMode,
            String requirement,
            String styleProfile,
            String designContextSnapshot,
            String htmlContent,
            String cssContent,
            String jsContent,
            String deployUrl
    ) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Long execute(RecordInput input, ToolContext context) {
        if (input == null || input.userId() == null) {
            return null;
        }
        try {
            GeneratedPage page = GeneratedPage.builder()
                    .userId(input.userId())
                    .projectId(input.projectId())
                    .requirementText(input.requirement())
                    .styleProfile(input.styleProfile())
                    .designSpecSnapshot(input.designContextSnapshot())
                    .html(input.htmlContent())
                    .css(input.cssContent())
                    .js(input.jsContent())
                    .isSaved(false)
                    .isExported(false)
                    .isDeleted(false)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            generatedPageMapper.insert(page);
            log.info("GenerationRecordTool 已保存生成快照 pageId={} userId={} projectId={} mode={}",
                    page.getId(), input.userId(), input.projectId(), input.generationMode());
            return page.getId();
        } catch (Exception e) {
            log.error("GenerationRecordTool 写入失败 userId={} projectId={}", input.userId(), input.projectId(), e);
            return null;
        }
    }
}
