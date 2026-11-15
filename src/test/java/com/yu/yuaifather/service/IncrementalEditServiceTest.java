package com.yu.yuaifather.service;

import com.yu.yuaifather.codegen.GeneratedProjectDirectory;
import com.yu.yuaifather.codegen.memory.ChatMemoryFactory;
import com.yu.yuaifather.codegen.memory.CompressingWindowChatMemory;
import com.yu.yuaifather.codegen.tools.FileReadTool;
import com.yu.yuaifather.codegen.tools.ToolManager;
import com.yu.yuaifather.mapper.AppMapper;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncrementalEditServiceTest {

    private static final AtomicLong IDS = new AtomicLong(881_000_000_000L);
    private final List<Path> created = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (Path path : created) {
            if (!Files.exists(path)) {
                continue;
            }
            try (var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // 清理失败不应掩盖断言
                    }
                });
            }
        }
    }

    @Test
    void missingDirectoryFailsForHtmlEvenWhenVueNamingWasTheOldDefault() {
        long appId = IDS.incrementAndGet();
        IncrementalEditService service = service(appId, CodeGenTypeEnum.HTML, new AtomicReference<>());

        assertEquals("项目目录不存在，请先生成代码", service.incrementalEdit(appId, "改标题"));
        String streamed = service.incrementalEditStream(appId, "改标题").blockFirst();
        assertTrue(streamed.contains("项目目录不存在，请先生成代码"));
    }

    @Test
    void htmlMultiFileAndVueProjectsCanBeEditedAfterGeneration() throws IOException {
        assertEdits(CodeGenTypeEnum.HTML, "index.html");
        assertEdits(CodeGenTypeEnum.MULTI_FILE, "index.html");
        assertEdits(CodeGenTypeEnum.VUE_PROJECT, "src/App.vue");
    }

    @Test
    void htmlEditDoesNotUseAnExistingVueDirectory() throws IOException {
        long appId = IDS.incrementAndGet();
        write(CodeGenTypeEnum.VUE_PROJECT, appId, "src/App.vue", "<template></template>");
        IncrementalEditService service = service(appId, CodeGenTypeEnum.HTML, new AtomicReference<>());

        assertEquals("项目目录不存在，请先生成代码", service.incrementalEdit(appId, "改标题"));
    }

    private void assertEdits(CodeGenTypeEnum type, String relative) throws IOException {
        long appId = IDS.incrementAndGet();
        write(type, appId, relative, "<h1>{{ r.name }}</h1>");
        AtomicReference<String> userText = new AtomicReference<>();
        IncrementalEditService service = service(appId, type, userText);

        String instruction = "把标题改成 {{ r.name }}，计数使用 {{ counts[cat.id] || 0 }}";
        assertEquals("修改完成", service.incrementalEdit(appId, instruction));
        assertTrue(userText.get().contains("【项目类型】" + type.getValue()), type.name());
        assertTrue(userText.get().contains("{{ r.name }}"), type.name());
        assertTrue(userText.get().contains("{{ counts[cat.id] || 0 }}"), type.name());
    }

    private IncrementalEditService service(long appId, CodeGenTypeEnum type, AtomicReference<String> userText) {
        AppMapper mapper = mock(AppMapper.class);
        when(mapper.selectOneById(appId)).thenReturn(App.builder()
                .id(appId)
                .codeGenType(type.getValue())
                .build());

        List<ChatMessage> stored = new ArrayList<>();
        CompressingWindowChatMemory memory = mock(CompressingWindowChatMemory.class);
        when(memory.messages()).thenAnswer(invocation -> List.copyOf(stored));
        doAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            return null;
        }).when(memory).add(any(ChatMessage.class));

        ChatMemoryFactory memoryFactory = mock(ChatMemoryFactory.class);
        when(memoryFactory.create(any())).thenReturn(memory);
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getAllTools()).thenReturn(new com.yu.yuaifather.codegen.tools.BaseTool[]{new FileReadTool()});

        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                chatRequest.messages().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .map(UserMessage::singleText)
                        .forEach(userText::set);
                return ChatResponse.builder().aiMessage(AiMessage.from("修改完成")).build();
            }
        };
        return new IncrementalEditService(model, null, toolManager, memoryFactory,
                mock(ChatHistoryService.class), new GeneratedProjectDirectory(mapper));
    }

    private void write(CodeGenTypeEnum type, long appId, String relative, String content) throws IOException {
        Path file = GeneratedProjectDirectory.directory(type, appId).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        created.add(GeneratedProjectDirectory.directory(type, appId));
    }
}
