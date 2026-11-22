package com.yu.yuaifather.media.screenshot;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.media.storage.LocalImageStorage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;

@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    /** 封面截图落地到本机静态目录，由 /api/static 提供访问，不再依赖 COS。 */
    @Resource
    private LocalImageStorage localImageStorage;

    /** 截图工具（Spring Bean，改造前为静态工具类） */
    @Resource
    private WebScreenshotUtils webScreenshotUtils;

    /**
     * 应用部署基础 URL，用于 SSRF 同源校验（与 AppServiceImpl 使用同一配置项）。
     */
    @Value("${app.deploy.base-url:http://localhost:8123/api/static}")
    private String deployBaseUrl;

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页 URL 不能为空");
        validateScreenshotOrigin(webUrl);
        String localPath = webScreenshotUtils.saveWebPageScreenshot(webUrl);
        ThrowUtils.throwIf(StrUtil.isBlank(localPath), ErrorCode.OPERATION_ERROR, "本地截图生成失败");
        try {
            File screenshotFile = new File(localPath);
            ThrowUtils.throwIf(!screenshotFile.exists(), ErrorCode.OPERATION_ERROR, "截图文件不存在");
            String ext = FileUtil.extName(screenshotFile);
            String url = localImageStorage.save(screenshotFile, StrUtil.blankToDefault(ext, "jpg"));
            ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.OPERATION_ERROR, "截图保存到本地失败");
            log.info("封面截图已保存到本地: {}", url);
            return url;
        } finally {
            File file = new File(localPath);
            if (file.exists()) {
                FileUtil.del(file.getParentFile());
            }
        }
    }

    /**
     * SSRF 防护：截图目标必须与配置的部署基础 URL 同源（scheme + host + port）。
     * <p>
     * 截图 URL 由后端根据 {@code app.deploy.base-url} 拼接而来，因此这里以它为白名单；
     * 本地开发时部署地址为 localhost，仍被允许，同时拒绝任意内网/本地地址。
     * </p>
     */
    private void validateScreenshotOrigin(String webUrl) {
        URI target;
        URI allowed;
        try {
            target = URI.create(webUrl.trim());
            allowed = URI.create(deployBaseUrl == null ? "" : deployBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "截图 URL 格式无效");
        }
        String targetScheme = target.getScheme();
        String allowedScheme = allowed.getScheme();
        if (!"http".equalsIgnoreCase(targetScheme) && !"https".equalsIgnoreCase(targetScheme)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "仅允许 HTTP 或 HTTPS URL");
        }
        boolean sameOrigin = targetScheme != null
                && targetScheme.equalsIgnoreCase(allowedScheme)
                && target.getHost() != null
                && target.getHost().equalsIgnoreCase(allowed.getHost())
                && target.getPort() == allowed.getPort();
        if (!sameOrigin) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "禁止访问非部署来源的地址");
        }
    }
}
