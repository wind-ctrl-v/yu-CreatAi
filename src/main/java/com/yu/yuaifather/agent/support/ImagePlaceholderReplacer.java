package com.yu.yuaifather.agent.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.agent.tools.ImageSearchTool;
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
 * 扫描指定项目目录下的所有代码文件，将 {@code {{IMG:keyword}}} 占位符
 * 替换为真实图片 URL（来自 {@link ImageSearchTool}）。
 * <p>
 * 同一 keyword 在单次替换过程中只搜索一次（请求级缓存），
 * 跨请求复用 ImageSearchTool 的 Caffeine 缓存。
 */
@Slf4j
@Component
public class ImagePlaceholderReplacer {

    /** 占位符正则：{{IMG:任意非花括号字符}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{IMG:([^{}]+?)\\}\\}");

    /** 仅替换文本类文件，避免误改二进制资源。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vue", "html", "htm", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "md"
    );

    private final ImageSearchTool imageSearchTool;

    @Autowired
    public ImagePlaceholderReplacer(ImageSearchTool imageSearchTool) {
        this.imageSearchTool = imageSearchTool;
    }

    /**
     * 替换指定应用目录下所有文件中的图片占位符。
     *
     * @param appId 应用 ID
     * @return 本次实际命中的 imageMap（keyword → URL）
     */
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

    /**
     * 仅替换给定的多文件代码字符串映射（用于 HTML / MULTI_FILE 等不落盘场景）。
     */
    public Map<String, String> replaceInStrings(Map<String, String> codeFiles) {
        if (codeFiles == null || codeFiles.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new HashMap<>();
        codeFiles.replaceAll((path, content) -> replaceContent(content, resolved));
        return resolved;
    }

    /** 在单个文件中执行替换，返回该文件的替换次数。 */
    private int replaceInFile(File file, Map<String, String> resolved) {
        String original;
        try {
            original = FileUtil.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取文件失败，跳过: {} msg={}", file.getAbsolutePath(), e.getMessage());
            return 0;
        }
        if (!original.contains("{{IMG:")) {
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
        if (StrUtil.isBlank(content) || !content.contains("{{IMG:")) {
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
                // ImageSearchTool 必定返回兜底图，理论上不该走到这里，做防御
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
