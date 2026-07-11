package com.yu.yuaifather.manager;

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

/**
 * 本地图片存储：将图片保存到静态资源目录，返回可通过 {@code StaticResourceController} 访问的 URL。
 * <p>
 * 用于替代 COS，适配「本地存储」部署形态。文生图生成的图片经此持久化后，
 * 由 {@code /api/static/{deployKey}/**} 对外提供访问。
 * <p>
 * 存储路径：{@code {code.output-root-dir}/_generated_images/{yyyy-MM-dd}/{uuid}.{ext}}
 * 访问 URL：{@code {app.deploy.base-url}/_generated_images/{yyyy-MM-dd}/{uuid}.{ext}}
 */
@Slf4j
@Component
public class LocalImageStorage {

    /** StaticResourceController 的静态资源根目录，默认与生成根目录一致。 */
    @Value("${code.output-root-dir:}")
    private String outputRootDir;

    /** 对外访问基础 URL，形如 http://localhost:8123/api/static。 */
    @Value("${app.deploy.base-url:http://localhost:8123/api/static}")
    private String deployBaseUrl;

    /** 静态资源访问的 deployKey（作为一级子目录），生成图片统一归入该目录。 */
    private static final String IMAGE_DEPLOY_KEY = "_generated_images";

    /**
     * 保存图片文件，返回可访问 URL；失败返回 null。
     *
     * @param source 已下载到本地的图片文件
     * @param ext    文件扩展名（不含点，如 png / jpg）
     */
    public String save(File source, String ext) {
        if (source == null || !source.exists()) {
            return null;
        }
        String root = StrUtil.isBlank(outputRootDir) ? AppConstant.CODE_OUTPUT_ROOT_DIR : outputRootDir;
        String safeExt = StrUtil.isBlank(ext) ? "png" : ext.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (StrUtil.isBlank(safeExt)) {
            safeExt = "png";
        }
        String date = LocalDate.now().toString();
        String fileName = IdUtil.fastSimpleUUID() + "." + safeExt;
        Path dest = Paths.get(root, IMAGE_DEPLOY_KEY, date, fileName).normalize();
        try {
            FileUtil.mkParentDirs(dest.toFile());
            FileUtil.copy(source, dest.toFile(), true);
            String base = deployBaseUrl.endsWith("/")
                    ? deployBaseUrl.substring(0, deployBaseUrl.length() - 1) : deployBaseUrl;
            String url = base + "/" + IMAGE_DEPLOY_KEY + "/" + date + "/" + fileName;
            log.info("图片已保存本地静态目录: {} -> {}", dest, url);
            return url;
        } catch (Exception e) {
            log.warn("保存图片到本地静态目录失败 dest={} msg={}", dest, e.getMessage());
            return null;
        }
    }
}
