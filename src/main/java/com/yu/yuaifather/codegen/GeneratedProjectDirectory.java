package com.yu.yuaifather.codegen;

import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.mapper.AppMapper;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 已生成代码的项目目录。
 * <p>
 * 目录名约定是 {@code {codeGenType}_{appId}}，覆盖 html、multi_file、vue_project。
 * 工具写入在类型已知时使用该目录（即使目录尚未创建，生成过程会补建）；
 * 增量编辑只接受已经存在的目录，并且在应用记录了类型时不改到另一种产物上。
 */
@Slf4j
@Component
public class GeneratedProjectDirectory {

    private final AppMapper appMapper;

    public GeneratedProjectDirectory(@Autowired(required = false) AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    /**
     * 供文件工具使用的项目根目录。类型已知时返回对应目录，不要求它已经存在。
     */
    public Path toolRoot(Long appId) {
        return toolRoot(appId, codeGenTypeOf(appId));
    }

    /**
     * 增量编辑可改的已生成目录。类型已知但目录不存在时返回空，避免改到另一种产物。
     */
    public Optional<LocatedProject> locateExisting(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        CodeGenTypeEnum preferred = parseType(codeGenTypeOf(appId));
        if (preferred != null) {
            Path path = directory(preferred, appId);
            return Files.isDirectory(path)
                    ? Optional.of(new LocatedProject(preferred, path))
                    : Optional.empty();
        }
        return newest(existingProjects(appId));
    }

    public static Path toolRoot(Long appId, String codeGenType) {
        CodeGenTypeEnum preferred = parseType(codeGenType);
        if (preferred != null) {
            return directory(preferred, appId);
        }
        return newest(existingProjects(appId))
                .map(LocatedProject::directory)
                .orElseGet(() -> directory(CodeGenTypeEnum.VUE_PROJECT, appId));
    }

    private static Optional<LocatedProject> newest(List<LocatedProject> existing) {
        return existing.stream()
                .max(Comparator.comparingLong(project -> lastModified(project.directory())));
    }

    public static Path directory(CodeGenTypeEnum type, Long appId) {
        String dirName = type.getValue() + "_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, dirName).toAbsolutePath().normalize();
    }

    private String codeGenTypeOf(Long appId) {
        if (appMapper == null || appId == null) {
            return null;
        }
        try {
            App app = appMapper.selectOneById(appId);
            return app == null ? null : app.getCodeGenType();
        } catch (RuntimeException e) {
            log.warn("读取应用代码类型失败，按磁盘目录回退 appId={} msg={}", appId, e.getMessage());
            return null;
        }
    }

    static CodeGenTypeEnum parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        CodeGenTypeEnum byValue = CodeGenTypeEnum.getEnumByValue(trimmed);
        if (byValue != null) {
            return byValue;
        }
        try {
            return CodeGenTypeEnum.valueOf(trimmed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<LocatedProject> existingProjects(Long appId) {
        List<LocatedProject> found = new ArrayList<>();
        if (appId == null) {
            return found;
        }
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            Path path = directory(type, appId);
            if (Files.isDirectory(path)) {
                found.add(new LocatedProject(type, path));
            }
        }
        return found;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public record LocatedProject(CodeGenTypeEnum type, Path directory) {
    }
}
