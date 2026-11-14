package com.yu.yuaifather.codegen;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 代码输出目录清理器：安全删除 {@code tmp/code_output} 下的应用项目目录。
 * <p>
 * 删除前用 {@code normalize + startsWith} 校验目标必须是输出根目录下的子目录，防止 {@code ../} 路径穿越；
 * 删除失败只记录 warn，不抛出异常（调用方按 best-effort 处理）。
 */
@Slf4j
@Component
public class ProjectDirCleaner {

    /**
     * 清理指定项目目录。
     *
     * @param dirName 相对于 {@link AppConstant#CODE_OUTPUT_ROOT_DIR} 的目录名，如 {@code vue_project_123}
     * @return 目录不存在或删除成功返回 true；目录名非法或删除失败返回 false
     */
    public boolean clean(String dirName) {
        if (StrUtil.isBlank(dirName)) {
            log.warn("清理项目目录失败：目录名为空");
            return false;
        }
        try {
            Path root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
            Path target = root.resolve(dirName).toAbsolutePath().normalize();
            // 防路径穿越：目标必须是输出根目录下的子目录，且不能是根目录本身
            if (target.equals(root) || !target.startsWith(root)) {
                log.warn("清理项目目录失败：目标路径非法 dirName={} target={}", dirName, target);
                return false;
            }
            if (!Files.exists(target)) {
                log.info("项目目录不存在，无需清理: {}", target);
                return true;
            }
            boolean deleted = FileUtil.del(target.toFile());
            if (deleted) {
                log.info("已删除项目目录: {}", target);
                return true;
            }
            log.warn("删除项目目录失败（可能被占用）: {}", target);
            return false;
        } catch (Exception e) {
            log.warn("清理项目目录失败 dirName={} msg={}", dirName, e.getMessage());
            return false;
        }
    }
}
