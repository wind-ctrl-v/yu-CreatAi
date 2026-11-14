package com.yu.yuaifather.codegen;

import com.yu.yuaifather.constant.AppConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectDirCleaner} 单元测试：正常删除、路径穿越拒绝、不存在目录、空目录名。
 * <p>
 * 使用 {@link AppConstant#CODE_OUTPUT_ROOT_DIR} 下带随机后缀的临时目录，避免影响真实生成物。
 */
class ProjectDirCleanerTest {

    private final ProjectDirCleaner cleaner = new ProjectDirCleaner();
    private final String dirName = "clean_test_" + UUID.randomUUID().toString().substring(0, 8);
    private final Path root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();

    @AfterEach
    void cleanup() throws IOException {
        Path target = root.resolve(dirName).normalize();
        if (target.startsWith(root) && Files.exists(target)) {
            try (var walk = Files.walk(target)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // ignore
                    }
                });
            }
        }
    }

    @Test
    void shouldDeleteExistingProjectDir() throws IOException {
        Path target = root.resolve(dirName);
        Files.createDirectories(target.resolve("dist"));
        Files.writeString(target.resolve("dist").resolve("index.html"), "<html></html>");

        assertTrue(cleaner.clean(dirName));
        assertFalse(Files.exists(target));
    }

    @Test
    void shouldReturnTrueWhenDirNotExists() {
        assertTrue(cleaner.clean("not_exists_" + UUID.randomUUID().toString().substring(0, 8)));
    }

    @Test
    void shouldRejectPathTraversal() {
        assertFalse(cleaner.clean("../" + dirName));
        assertFalse(cleaner.clean("a/../../" + dirName));
        // 根目录本身也不允许删除
        assertFalse(cleaner.clean("."));
    }

    @Test
    void shouldRejectBlankDirName() {
        assertFalse(cleaner.clean(null));
        assertFalse(cleaner.clean(""));
        assertFalse(cleaner.clean("   "));
    }
}
