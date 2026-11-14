package com.yu.yuaifather.codegen.build;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RuntimeUtil;
import com.yu.yuaifather.config.BuildProperties;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;


@Slf4j
@Component
public class VueProjectBuilder {

    /** 构建配置：锁等待 / npm 超时 / 构建后清理，绑定 {@code codegen.build.*}。 */
    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    @Qualifier("buildExecutor")
    private Executor buildExecutor;

    /**
     * 构建串行锁（公平锁）：2C4G 单机下 npm install + build 内存占用高，
     * 同一时刻只允许一个构建任务执行，避免并发构建打满内存。
     */
    private final ReentrantLock buildLock = new ReentrantLock(true);

    /**
     * 异步构建项目（不阻塞主流程）
     *
     * @param projectPath 项目路径
     */
    public void buildProjectAsync(String projectPath) {
        buildExecutor.execute(() -> {
            try {
                buildProject(projectPath);
            } catch (Exception e) {
                log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
            }
        });
    }



    /**
     * 执行命令
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否执行成功
     */
    private boolean executeCommand(File workingDir, String command, int timeoutSeconds) {
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
            Process process = RuntimeUtil.exec(
                    null,
                    workingDir,
                    command.split("\\s+") // 命令分割为数组
            );
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return true;
            } else {
                log.error("命令执行失败，退出码: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", command, e.getMessage());
            return false;
        }
    }


    /**
     * 执行 npm install 命令（超时时间可配置，默认 300 秒）
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        String command = String.format("%s install", buildCommand("npm"));
        return executeCommand(projectDir, command, buildProperties.getInstallTimeoutSeconds());
    }

    /**
     * 执行 npm run build 命令（超时时间可配置，默认 180 秒）
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        String command = String.format("%s run build", buildCommand("npm"));
        return executeCommand(projectDir, command, buildProperties.getBuildTimeoutSeconds());
    }


    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }


    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }


    /**
     * 构建 Vue 项目（所有构建经公平锁串行执行）。
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     * @throws BusinessException 等待构建锁超时（当前已有构建任务在执行）时抛出
     */
    public boolean buildProject(String projectPath) {
        acquireBuildLock();
        try {
            return doBuildProject(projectPath);
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * 获取构建锁：{@code lock-wait-seconds <= 0} 表示无限等待，否则最多等待配置秒数。
     * 未获取到锁时抛业务异常，提示用户稍后再试。
     */
    private void acquireBuildLock() {
        int waitSeconds = buildProperties.getLockWaitSeconds();
        boolean acquired;
        try {
            if (waitSeconds <= 0) {
                buildLock.lock();
                acquired = true;
            } else {
                acquired = buildLock.tryLock(waitSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "等待构建任务时被中断，请稍后再试");
        }
        if (!acquired) {
            log.warn("获取构建锁超时（等待 {} 秒），当前已有构建任务在执行", waitSeconds);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "当前有构建任务正在执行，请稍后再试");
        }
    }

    /**
     * 真正的构建流程（调用方已持有构建锁）。
     */
    private boolean doBuildProject(String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectPath);
        // 执行 npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }
        // 执行 npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        // 构建成功后按配置清理 node_modules（默认关闭，保持旧行为；失败仅告警）
        cleanupNodeModulesIfNeeded(projectDir);
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        return true;
    }

    /**
     * 按 {@code codegen.build.cleanup-node-modules} 配置清理 node_modules 以节省磁盘。
     * <p>清理失败仅记录 warn，不影响构建结果。</p>
     */
    private void cleanupNodeModulesIfNeeded(File projectDir) {
        if (!buildProperties.isCleanupNodeModules()) {
            return;
        }
        File nodeModulesDir = new File(projectDir, "node_modules");
        if (!nodeModulesDir.exists()) {
            return;
        }
        try {
            boolean deleted = FileUtil.del(nodeModulesDir);
            if (deleted) {
                log.info("构建成功，已清理 node_modules 目录: {}", nodeModulesDir.getAbsolutePath());
            } else {
                log.warn("构建成功，但清理 node_modules 失败（目录可能被占用）: {}", nodeModulesDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("构建成功，但清理 node_modules 异常: {}，原因: {}", nodeModulesDir.getAbsolutePath(), e.getMessage());
        }
    }

}
