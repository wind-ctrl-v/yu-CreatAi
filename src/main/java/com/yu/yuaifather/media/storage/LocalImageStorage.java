package com.yu.yuaifather.media.storage;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

@Slf4j
@Component
public class LocalImageStorage {

    @Value("${code.output-root-dir:}")
    private String outputRootDir;

    @Value("${app.deploy.base-url:http://localhost:8123/api/static}")
    private String deployBaseUrl;

    private static final String IMAGE_DEPLOY_KEY = "_generated_images";

    public String save(File source, String ext) {
        if (source == null || !source.exists()) return null;
        String root = StrUtil.isBlank(outputRootDir) ? AppConstant.CODE_OUTPUT_ROOT_DIR : outputRootDir;
        String safeExt = StrUtil.isBlank(ext) ? "png" : ext.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (StrUtil.isBlank(safeExt)) safeExt = "png";
        String date = LocalDate.now().toString();
        String fileName = IdUtil.fastSimpleUUID() + "." + safeExt;
        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        Path dest = rootPath.resolve(Paths.get(IMAGE_DEPLOY_KEY, date, fileName)).normalize();
        if (!dest.startsWith(rootPath)) return null;
        try {
            FileUtil.mkParentDirs(dest.toFile());
            FileUtil.copy(source, dest.toFile(), true);
            String base = deployBaseUrl.endsWith("/")
                    ? deployBaseUrl.substring(0, deployBaseUrl.length() - 1) : deployBaseUrl;
            return base + "/" + IMAGE_DEPLOY_KEY + "/" + date + "/" + fileName;
        } catch (Exception e) {
            log.warn("保存图片到本地静态目录失败 msg={}", e.getMessage());
            return null;
        }
    }
}
