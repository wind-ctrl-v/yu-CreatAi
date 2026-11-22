package com.yu.yuaifather.service.deploy;

import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.AppService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 生成成功后走与「部署」按钮相同的本地部署（{@link AppService#deployApp}）。
 * <p>
 * 该方法会写入 {@code deployKey} / {@code deployedTime}，并异步触发既有封面截图。
 * 失败只记日志并返回失败结果，不回滚生成。
 * <p>
 * 这里不读取 {@code agent.deploy.enabled}，也不会调用 COS {@code DeployTool}。
 * 该开关仍默认关闭，只控制流水线末尾的 COS 上传。
 */
@Slf4j
@Service
public class LocalAutoDeployService {

    private final AppService appService;

    /**
     * 与生成 SSE 解耦的线程池：客户端断开后部署仍继续，以便首页卡片拿到 deployKey 与封面。
     * Vue 构建本身还有进程内公平锁，池子只需要很小。
     */
    private final ThreadPoolExecutor executor;

    public LocalAutoDeployService(AppService appService) {
        this.appService = appService;
        this.executor = new ThreadPoolExecutor(
                1,
                2,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "local-auto-deploy");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 调用手动部署的同一实现。权限仍由 {@code deployApp} 校验（仅应用所有者）。
     */
    public AutoDeployOutcome tryDeploy(Long appId, User loginUser) {
        try {
            String deployUrl = appService.deployApp(appId, loginUser);
            log.info("生成完成，已自动本地部署 appId={} url={}", appId, deployUrl);
            return AutoDeployOutcome.success(deployUrl);
        } catch (Exception e) {
            log.warn("自动本地部署失败，生成结果已保留，可手动部署 appId={} msg={}",
                    appId, e.getMessage());
            return AutoDeployOutcome.failed();
        }
    }

    public Executor executor() {
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
