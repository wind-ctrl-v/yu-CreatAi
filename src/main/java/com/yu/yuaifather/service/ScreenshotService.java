package com.yu.yuaifather.service;

public interface ScreenshotService {

    /**
     * 截取网页并上传
     * @param webUrl
     * @return
     */
    String generateAndUploadScreenshot(String webUrl);
}
