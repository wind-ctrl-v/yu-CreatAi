package com.yu.yuaifather.tool.engine;

import com.yu.yuaifather.media.storage.LocalImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchImageArchiverTest {

    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @TempDir
    Path tempDir;

    private LocalImageStorage storage;
    private List<String> fetched;

    @BeforeEach
    void setUp() {
        storage = new LocalImageStorage();
        ReflectionTestUtils.setField(storage, "outputRootDir", tempDir.resolve("code_output").toString());
        ReflectionTestUtils.setField(storage, "deployBaseUrl", "http://localhost:8123/api/static");
        fetched = new ArrayList<>();
    }

    @Test
    void archivesWhitelistedImageAndReturnsLocalUrl() {
        SearchImageArchiver archiver = archiver((url) -> jpeg(200));

        String local = archiver.archive("https://cdn.pixabay.com/get/photo.jpg");

        assertEquals(List.of("https://cdn.pixabay.com/get/photo.jpg"), fetched);
        assertTrue(local.startsWith("http://localhost:8123/api/static/_generated_images/"));
        assertTrue(local.endsWith(".jpg"));
        String suffix = local.substring("http://localhost:8123/api/static/".length());
        assertTrue(Files.isRegularFile(tempDir.resolve("code_output").resolve(suffix)));
    }

    @Test
    void doesNotFetchOrKeepExpiredHtml() {
        SearchImageArchiver archiver = archiver((url) ->
                new SearchImageArchiver.RemoteImageResponse(400, null, "This URL is invalid or has expired.".getBytes()));

        assertNull(archiver.archive("https://pixabay.com/get/expired.jpg"));
    }

    @Test
    void doesNotRequestHostOutsideWhitelist() {
        SearchImageArchiver archiver = archiver((url) -> jpeg(200));

        assertNull(archiver.archive("https://evil.example/a.jpg"));
        assertNull(archiver.archive("http://cdn.pixabay.com/a.jpg"));
        assertTrue(fetched.isEmpty());
    }

    @Test
    void followsRedirectOnlyOntoWhitelistedHost() {
        SearchImageArchiver archiver = archiver((url) -> {
            if (url.contains("picsum.photos/seed")) {
                return new SearchImageArchiver.RemoteImageResponse(302, "https://fastly.picsum.photos/photo.jpg", null);
            }
            return new SearchImageArchiver.RemoteImageResponse(200, null, PNG);
        });

        String local = archiver.archive("https://picsum.photos/seed/demo/1200/800");

        assertEquals(List.of(
                "https://picsum.photos/seed/demo/1200/800",
                "https://fastly.picsum.photos/photo.jpg"), fetched);
        assertTrue(local.endsWith(".png"));
    }

    @Test
    void stopsWhenRedirectLeavesWhitelist() {
        SearchImageArchiver archiver = archiver((url) ->
                new SearchImageArchiver.RemoteImageResponse(302, "https://attacker.example/internal", null));

        assertNull(archiver.archive("https://loremflickr.com/1200/800/fabric"));
        assertEquals(List.of("https://loremflickr.com/1200/800/fabric"), fetched);
    }

    private SearchImageArchiver archiver(java.util.function.Function<String, SearchImageArchiver.RemoteImageResponse> handler) {
        SearchImageArchiver.RemoteImageClient client = url -> {
            fetched.add(url);
            return handler.apply(url);
        };
        return new SearchImageArchiver(storage, client, SearchImageArchiverTest::allowed);
    }

    private static boolean allowed(String url) {
        return url.startsWith("https://cdn.pixabay.com/")
                || url.startsWith("https://pixabay.com/")
                || url.startsWith("https://picsum.photos/")
                || url.startsWith("https://fastly.picsum.photos/")
                || url.startsWith("https://loremflickr.com/");
    }

    private static SearchImageArchiver.RemoteImageResponse jpeg(int status) {
        return new SearchImageArchiver.RemoteImageResponse(status, null, JPEG);
    }
}
