package com.yu.yuaifather.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticResourceControllerTest {

    @Test
    void servesGeneratedBitmapsAsImages() {
        assertEquals("image/jpeg", StaticResourceController.contentType("/tmp/cover.JPG"));
        assertEquals("image/jpeg", StaticResourceController.contentType("photo.jpeg"));
        assertEquals("image/png", StaticResourceController.contentType("photo.png"));
        assertEquals("image/gif", StaticResourceController.contentType("photo.gif"));
        assertEquals("image/webp", StaticResourceController.contentType("photo.webp"));
        assertEquals("image/avif", StaticResourceController.contentType("photo.avif"));
        assertEquals("application/octet-stream", StaticResourceController.contentType("icon.svg"));
    }
}
