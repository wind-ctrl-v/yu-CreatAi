package com.yu.yuaifather.tool.engine;

import com.yu.yuaifather.config.ImageProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageSearchEngineArchiveTest {

    @Test
    void replacesRemoteHitWithArchivedUrl() {
        SearchImageArchiver archiver = mock(SearchImageArchiver.class);
        String remote = "https://cdn.pixabay.com/get/photo_1280.jpg";
        String local = "http://localhost:8123/api/static/_generated_images/2026-11-25/photo.jpg";
        when(archiver.archive(remote)).thenReturn(local);
        ImageSearchEngine engine = engine(archiver, true);

        ImageSearchEngine.SearchHit hit = engine.host(remote, true);

        assertEquals(local, hit.url());
        assertTrue(hit.cacheable());
    }

    @Test
    void dropsRemoteUrlWhenArchiveFails() {
        SearchImageArchiver archiver = mock(SearchImageArchiver.class);
        String remote = "https://cdn.pixabay.com/get/expired.jpg";
        when(archiver.archive(remote)).thenReturn(null);
        ImageSearchEngine engine = engine(archiver, true);

        assertNull(engine.host(remote, true));
    }

    @Test
    void doesNotArchiveUnsafeUrl() {
        SearchImageArchiver archiver = mock(SearchImageArchiver.class);
        ImageSearchEngine engine = engine(archiver, false);

        assertNull(engine.host("https://cdn.pixabay.com/get/photo.jpg", true));
        verify(archiver, never()).archive("https://cdn.pixabay.com/get/photo.jpg");
    }

    @Test
    void rejectsNonWhitelistedAndInsecureUrlsWithoutFetching() {
        ImageSearchEngine engine = new ImageSearchEngine(
                new ImageProperties(), mock(TextToImageEngine.class), mock(SearchImageArchiver.class));

        assertFalse(engine.isSafe("http://cdn.pixabay.com/a.jpg"));
        assertFalse(engine.isSafe("https://evil.example/a.jpg"));
        assertFalse(engine.isSafe("https://user:pass@cdn.pixabay.com/a.jpg"));
        assertFalse(engine.isSafe(null));
    }

    private ImageSearchEngine engine(SearchImageArchiver archiver, boolean safe) {
        return new ImageSearchEngine(new ImageProperties(), mock(TextToImageEngine.class), archiver) {
            @Override
            boolean isSafe(String url) {
                return safe;
            }
        };
    }
}
