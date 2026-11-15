package com.yu.yuaifather.codegen;

import com.yu.yuaifather.codegen.tools.FileReadTool;
import com.yu.yuaifather.mapper.AppMapper;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedProjectDirectoryTest {

    private static final AtomicLong IDS = new AtomicLong(880_000_000_000L);
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
    void locatesHtmlMultiFileAndVueDirectoriesByRecordedType() throws IOException {
        assertLocated(CodeGenTypeEnum.HTML, "index.html", "<h1>html</h1>");
        assertLocated(CodeGenTypeEnum.MULTI_FILE, "style.css", "body{}");
        assertLocated(CodeGenTypeEnum.VUE_PROJECT, "src/App.vue", "<template></template>");
    }

    @Test
    void recordedHtmlTypeDoesNotFallThroughToVueDirectory() throws IOException {
        long appId = IDS.incrementAndGet();
        write(CodeGenTypeEnum.VUE_PROJECT, appId, "src/App.vue", "<template>vue</template>");
        GeneratedProjectDirectory directory = directoryFor(appId, CodeGenTypeEnum.HTML.getValue());

        assertTrue(directory.locateExisting(appId).isEmpty());
        assertEquals(GeneratedProjectDirectory.directory(CodeGenTypeEnum.HTML, appId),
                directory.toolRoot(appId));
    }

    @Test
    void singleGeneratedDirectoryIsUsedWhenTypeIsUnknown() throws IOException {
        long appId = IDS.incrementAndGet();
        write(CodeGenTypeEnum.MULTI_FILE, appId, "index.html", "<p>multi</p>");
        GeneratedProjectDirectory directory = new GeneratedProjectDirectory(null);

        Optional<GeneratedProjectDirectory.LocatedProject> located = directory.locateExisting(appId);
        assertTrue(located.isPresent());
        assertEquals(CodeGenTypeEnum.MULTI_FILE, located.get().type());
        assertEquals(located.get().directory(), GeneratedProjectDirectory.toolRoot(appId, null));
    }

    @Test
    void toolReadsTheDirectoryMatchingTheAppType() throws IOException {
        long appId = IDS.incrementAndGet();
        write(CodeGenTypeEnum.HTML, appId, "index.html", "html-body");
        write(CodeGenTypeEnum.VUE_PROJECT, appId, "index.html", "vue-body");

        FileReadTool tool = new FileReadTool();
        ReflectionTestUtils.setField(tool, "projectDirectory",
                directoryFor(appId, CodeGenTypeEnum.HTML.getValue()));

        assertEquals("html-body", tool.readFile("index.html", appId));
        assertFalse(tool.readFile("index.html", appId).contains("vue-body"));
    }

    @Test
    void unknownTypeWithSeveralDirectoriesUsesTheSameNewestProjectForEditAndTools() throws IOException {
        long appId = IDS.incrementAndGet();
        Path html = write(CodeGenTypeEnum.HTML, appId, "index.html", "html");
        Path vue = write(CodeGenTypeEnum.VUE_PROJECT, appId, "src/App.vue", "vue");
        Files.setLastModifiedTime(html, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(vue, java.nio.file.attribute.FileTime.fromMillis(2_000));

        GeneratedProjectDirectory directory = new GeneratedProjectDirectory(null);
        Optional<GeneratedProjectDirectory.LocatedProject> located = directory.locateExisting(appId);
        assertTrue(located.isPresent());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, located.get().type());
        assertEquals(located.get().directory(), directory.toolRoot(appId));
        assertEquals(located.get().directory(), GeneratedProjectDirectory.toolRoot(appId, null));
    }

    @Test
    void toolRootWithoutMapperStillDefaultsToVueWhenNothingExists() {
        long appId = IDS.incrementAndGet();
        Path root = GeneratedProjectDirectory.toolRoot(appId, null);
        assertTrue(root.getFileName().toString().startsWith(CodeGenTypeEnum.VUE_PROJECT.getValue() + "_"));
        assertFalse(Files.exists(root));
    }

    private void assertLocated(CodeGenTypeEnum type, String relative, String content) throws IOException {
        long appId = IDS.incrementAndGet();
        write(type, appId, relative, content);
        GeneratedProjectDirectory directory = directoryFor(appId, type.getValue());

        Optional<GeneratedProjectDirectory.LocatedProject> located = directory.locateExisting(appId);
        assertTrue(located.isPresent(), type.name());
        assertEquals(type, located.get().type(), type.name());
        assertEquals(GeneratedProjectDirectory.directory(type, appId), located.get().directory(), type.name());
        assertEquals(located.get().directory(), directory.toolRoot(appId), type.name());
    }

    private GeneratedProjectDirectory directoryFor(long appId, String codeGenType) {
        AppMapper mapper = mock(AppMapper.class);
        when(mapper.selectOneById(appId)).thenReturn(App.builder()
                .id(appId)
                .codeGenType(codeGenType)
                .build());
        return new GeneratedProjectDirectory(mapper);
    }

    private Path write(CodeGenTypeEnum type, long appId, String relative, String content) throws IOException {
        Path directory = GeneratedProjectDirectory.directory(type, appId);
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        created.add(directory);
        return directory;
    }
}
