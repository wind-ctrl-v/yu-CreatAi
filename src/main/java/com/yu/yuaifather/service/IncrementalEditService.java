package com.yu.yuaifather.service;

import com.yu.yuaifather.agent.codegen.fileops.*;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;

/**
 * 增量编辑服务：利用 AI + 文件工具对已有项目做原地修改，不重新生成整个项目。
 */
@Slf4j
@Service
public class IncrementalEditService {

    private final ChatModel chatModel;
    private final ToolManager toolManager;

    public IncrementalEditService(ChatModel chatModel, ToolManager toolManager) {
        this.chatModel = chatModel;
        this.toolManager = toolManager;
    }

    interface IncrementalEditAi {
        @SystemMessage("""
                你是一个前端代码编辑器。请根据用户的修改要求，对项目中的现有文件进行精准修改。
                规则：
                1. 先用 readFile 查看需要修改的文件当前内容
                2. 仅修改用户要求的部分，其他代码保持原样不变
                3. 如需编辑样式，只修改相关 CSS/样式部分
                4. 如需编辑结构，只修改相关 HTML/模板部分
                5. 修改完成后简洁回复"修改完成"，不要输出无关解释
                """)
        String edit(String userRequest);
    }

    /**
     * 对已生成的项目执行增量修改。
     */
    public String incrementalEdit(Long appId, String instruction) {
        String dirName = CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, dirName);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "项目目录不存在，请先生成代码";
        }

        // 使用短记忆，appId 传给工具（@ToolMemoryId 通过 chatMemoryProvider 绑定）
        IncrementalEditAi ai = AiServices.builder(IncrementalEditAi.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .tools(toolManager.getAllTools())
                .build();

        try {
            String result = ai.edit("修改项目 vue_project_" + appId + " 中的文件。\n" + instruction);
            log.info("增量编辑完成 appId={}", appId);
            return result;
        } catch (Exception e) {
            log.error("增量编辑失败 appId={}", appId, e);
            return "编辑失败: " + e.getMessage();
        }
    }
}
