package com.yu.yuaifather.media.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalImageStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void saveCopiesFileAndReturnsStaticUrl() throws Exception {
        LocalImageStorage storage = new LocalImageStorage();
        Path root = tempDir.resolve("code_output");
        Files.createDirectories(root);
        ReflectionTestUtils.setField(storage, "outputRootDir", root.toString());
        ReflectionTestUtils.setField(storage, "deployBaseUrl", "http://localhost:8123/api/static");

        File source = tempDir.resolve("cover.jpg").toFile();
        Files.write(source.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

        String url = storage.save(source, "jpg");
        assertNotNull(url);
        assertTrue(url.startsWith("http://localhost:8123/api/static/_generated_images/"));
        assertTrue(url.endsWith(".jpg"));

        String suffix = url.substring("http://localhost:8123/api/static/".length());
        Path saved = root.resolve(suffix);
        assertTrue(Files.isRegularFile(saved), "saved file should exist at " + saved);
        assertTrue(Files.size(saved) > 0);
    }
}
