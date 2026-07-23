package com.yu.yuaifather.media.screenshot;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.media.storage.CosManager;
import com.yu.yuaifather.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager;

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页 URL 不能为空");
        String localPath = WebScreenshotUtils.saveWebPageScreenshot(webUrl);
        ThrowUtils.throwIf(StrUtil.isBlank(localPath), ErrorCode.OPERATION_ERROR, "本地截图生成失败");
        try {
            File screenshotFile = new File(localPath);
            ThrowUtils.throwIf(!screenshotFile.exists(), ErrorCode.OPERATION_ERROR, "截图文件不存在");
            String fileName = UUID.randomUUID().toString().substring(0, 8) + "_compressed.jpg";
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String url = cosManager.uploadFile(String.format("/screenshots/%s/%s", datePath, fileName), screenshotFile);
            ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.OPERATION_ERROR, "截图上传对象存储失败");
            return url;
        } finally {
            File file = new File(localPath);
            if (file.exists()) FileUtil.del(file.getParentFile());
        }
    }
}
