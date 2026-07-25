package com.yu.yuaifather.codegen.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 文件目录读取工具
 * 使用 Hutool 简化文件操作
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    /**
     * 路径中包含以下任一片段则忽略（不仅匹配文件名，也匹配路径中任意父目录）
     */
    private static final Set<String> IGNORED_PATH_SEGMENTS = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage",
            "esbuild", "rollup", "vite", "nanoid", "picocolors",
            "source-map", "sourcemap-codec", "postcss", "@babel",
            "@vue", "@rollup", "magic-string", "estree-walker",
            "bin-trie", "decode-data", "encode-html"
    );

    /**
     * 需要忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock", ".map", ".flow", ".applescript"
    );

    private static final int MAX_OUTPUT_FILES = 150;

    @Tool("读取目录结构，获取指定目录下的文件和子目录（最多展示 " + MAX_OUTPUT_FILES + " 项）")
    public String readDir(
            @P("目录的相对路径，如 \"src\" 查看源码目录，\"\" 或 \".\" 查看根目录")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = resolveSafePath(relativeDirPath, appId);
            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            StringBuilder sb = new StringBuilder();
            String displayPath = (relativeDirPath == null || relativeDirPath.isEmpty() || ".".equals(relativeDirPath))
                    ? "项目根目录" : relativeDirPath;
            sb.append(displayPath).append("/\n");

            List<File> allFiles = FileUtil.loopFiles(targetDir, this::shouldInclude);
            // 截断过长的列表
            int total = allFiles.size();
            if (total > MAX_OUTPUT_FILES) {
                allFiles = allFiles.subList(0, MAX_OUTPUT_FILES);
            }

            allFiles.stream()
                    .sorted((f1, f2) -> {
                        int d1 = getRelativeDepth(targetDir, f1);
                        int d2 = getRelativeDepth(targetDir, f2);
                        if (d1 != d2) return Integer.compare(d1, d2);
                        return f1.getPath().compareTo(f2.getPath());
                    })
                    .forEach(file -> {
                        int depth = getRelativeDepth(targetDir, file);
                        String indent = "  ".repeat(depth);
                        sb.append(indent);
                        if (file.isDirectory()) {
                            sb.append(file.getName()).append("/");
                        } else {
                            sb.append(file.getName());
                        }
                        sb.append("\n");
                    });

            if (total > MAX_OUTPUT_FILES) {
                sb.append("... 还有 ").append(total - MAX_OUTPUT_FILES).append(" 个项目未显示\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("读取目录结构失败: {}", relativeDirPath, e);
            return "读取目录结构失败: " + e.getMessage();
        }
    }

    /**
     * 计算文件相对于根目录的深度
     */
    private int getRelativeDepth(File root, File file) {
        Path rootPath = root.toPath().normalize();
        Path filePath = file.toPath().normalize();
        Path relative = rootPath.relativize(filePath);
        return relative.getNameCount() - 1;
    }

    /**
     * 判断文件/目录是否应该包含在输出中。
     * 使用完整相对路径判断，而非仅文件名。
     */
    private boolean shouldInclude(File file) {
        String filePath = file.toPath().normalize().toString().replace('\\', '/');
        // 检查路径中是否包含忽略段
        for (String seg : IGNORED_PATH_SEGMENTS) {
            if (filePath.contains("/" + seg + "/") || filePath.endsWith("/" + seg)) {
                return false;
            }
        }
        // 检查文件扩展名
        String name = file.getName();
        for (String ext : IGNORED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return false;
            }
        }
        return true;
    }


    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录结构";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = arguments.getStr("relativeDirPath");
        if(StrUtil.isEmpty(relativeDirPath)){
            relativeDirPath = "根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
    }
}
