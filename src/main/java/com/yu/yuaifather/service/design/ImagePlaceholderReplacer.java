package com.yu.yuaifather.service.design;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.tool.engine.ImageSearchEngine;
import com.yu.yuaifather.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片占位符替换器。
 * <p>
 * 扫描指定项目目录下的所有代码文件，将 {@code @@IMG:keyword@@} 占位符
 * 替换为真实图片 URL（来自 {@link ImageSearchEngine}）。
 */
@Slf4j
@Component
public class ImagePlaceholderReplacer {

    private static final Pattern PLACEHOLDER = Pattern.compile("@@IMG:([^@]+?)@@");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vue", "html", "htm", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "md"
    );

    private final ImageSearchEngine imageSearchTool;

    @Autowired
    public ImagePlaceholderReplacer(ImageSearchEngine imageSearchTool) {
        this.imageSearchTool = imageSearchTool;
    }

    public Map<String, String> replaceAll(Long appId) {
        Path projectRoot = resolveProjectRoot(appId);
        if (!FileUtil.exist(projectRoot.toFile())) {
            log.warn("项目目录不存在，跳过图片占位符替换: {}", projectRoot);
            return Map.of();
        }
        Map<String, String> resolved = new ConcurrentHashMap<>();
        Collection<File> files = FileUtil.loopFiles(projectRoot.toFile(), this::isTextFile);
        int totalReplacements = 0;
        for (File file : files) {
            totalReplacements += replaceInFile(file, resolved);
        }
        log.info("图片占位符替换完成 appId={} 文件数={} 替换数={} 关键词数={}",
                appId, files.size(), totalReplacements, resolved.size());
        return resolved;
    }

    public Map<String, String> replaceInStrings(Map<String, String> codeFiles) {
        if (codeFiles == null || codeFiles.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new HashMap<>();
        codeFiles.replaceAll((path, content) -> replaceContent(content, resolved));
        return resolved;
    }

    private int replaceInFile(File file, Map<String, String> resolved) {
        String original;
        try {
            original = FileUtil.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取文件失败，跳过: {} msg={}", file.getAbsolutePath(), e.getMessage());
            return 0;
        }
        if (!original.contains("@@IMG:")) {
            return 0;
        }
        ReplaceResult r = replaceWithCount(original, resolved);
        if (r.count > 0) {
            try {
                FileUtil.writeString(r.text, file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("写回文件失败: {} msg={}", file.getAbsolutePath(), e.getMessage());
                return 0;
            }
        }
        return r.count;
    }

    private String replaceContent(String content, Map<String, String> resolved) {
        return replaceWithCount(content, resolved).text;
    }

    private ReplaceResult replaceWithCount(String content, Map<String, String> resolved) {
        if (StrUtil.isBlank(content) || !content.contains("@@IMG:")) {
            return new ReplaceResult(content, 0);
        }
        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        Set<String> missingLogged = new HashSet<>();
        while (matcher.find()) {
            String keyword = matcher.group(1).trim();
            String url = resolved.computeIfAbsent(keyword, imageSearchTool::search);
            if (StrUtil.isBlank(url)) {
                if (missingLogged.add(keyword)) {
                    log.warn("关键词 {} 未取得 URL，保留原占位符", keyword);
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(url));
                count++;
            }
        }
        matcher.appendTail(sb);
        return new ReplaceResult(sb.toString(), count);
    }

    private boolean isTextFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String ext = FileUtil.extName(file);
        return ext != null && TEXT_EXTENSIONS.contains(ext.toLowerCase());
    }

    private Path resolveProjectRoot(Long appId) {
        String projectDirName = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    private record ReplaceResult(String text, int count) {}
}
