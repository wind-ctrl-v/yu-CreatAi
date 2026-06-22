package com.yu.yuaifather.tool.system;

import cn.hutool.core.io.FileUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.codegen.core.VueProjectBuilder;
import com.yu.yuaifather.config.CosClientConfig;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.manager.CosManager;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * DeployTool：构建 + 部署（System Tool，零 LLM）。
 * <p>
 * 从 DeployAgent 改名重构而来。
 * <p>
 * 安全约束：仅上传 dist 内文件，路径校验防穿越；凭证不外泄。
 * 仅 Orchestrator 可调用，不暴露给 Agent。
 */
@Slf4j
@Component
public class DeployTool implements Agent, SystemTool<Long, String> {

    public static final String TOOL_NAME = "DeployTool";

    private final VueProjectBuilder vueProjectBuilder;
    private final CosManager cosManager;
    private final CosClientConfig cosClientConfig;

    @Autowired
    public DeployTool(VueProjectBuilder vueProjectBuilder,
                      CosManager cosManager,
                      CosClientConfig cosClientConfig) {
        this.vueProjectBuilder = vueProjectBuilder;
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(TOOL_NAME, "构建并部署中"));
            try {
                Path projectRoot = projectRoot(ctx.getAppId());
                if (!FileUtil.exist(projectRoot.toFile())) {
                    throw new IllegalStateException("项目目录不存在: " + projectRoot);
                }
                boolean ok = vueProjectBuilder.buildProject(projectRoot.toString());
                if (!ok) {
                    throw new IllegalStateException("Vue 项目构建失败");
                }
                Path dist = projectRoot.resolve("dist").normalize();
                if (!dist.startsWith(projectRoot) || !FileUtil.exist(dist.toFile())) {
                    throw new IllegalStateException("dist 目录不存在或路径非法");
                }
                String keyPrefix = "projects/" + ctx.getAppId() + "/";
                int count = uploadRecursive(dist.toFile(), dist.toFile(), keyPrefix);
                String indexKey = keyPrefix + "index.html";
                String url = cosClientConfig.getHost() + indexKey;
                ctx.getBlackboard().putDeployUrl(url);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(TOOL_NAME)
                        .content("部署完成 files=" + count + " url=" + url)
                        .data(url)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("DeployTool 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(TOOL_NAME, "部署失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SystemTool 同步执行：构建并部署，返回访问 URL。
     * 仅 Orchestrator 可调用，LLM Agent 无权访问。
     */
    @Override
    public String execute(Long appId, ToolContext context) {
        try {
            Path projectRoot = projectRoot(appId);
            if (!FileUtil.exist(projectRoot.toFile())) {
                throw new IllegalStateException("项目目录不存在: " + projectRoot);
            }
            boolean ok = vueProjectBuilder.buildProject(projectRoot.toString());
            if (!ok) {
                throw new IllegalStateException("Vue 项目构建失败");
            }
            Path dist = projectRoot.resolve("dist").normalize();
            if (!dist.startsWith(projectRoot) || !FileUtil.exist(dist.toFile())) {
                throw new IllegalStateException("dist 目录不存在或路径非法");
            }
            String keyPrefix = "projects/" + appId + "/";
            int count = uploadRecursive(dist.toFile(), dist.toFile(), keyPrefix);
            String indexKey = keyPrefix + "index.html";
            String url = cosClientConfig.getHost() + indexKey;
            log.info("DeployTool 同步部署完成 appId={} files={} url={}", appId, count, url);
            return url;
        } catch (Exception e) {
            log.error("DeployTool 同步部署失败 appId={}", appId, e);
            throw new RuntimeException("部署失败: " + e.getMessage(), e);
        }
    }

    private int uploadRecursive(File distRoot, File current, String keyPrefix) {
        int count = 0;
        Collection<File> files = FileUtil.loopFiles(current, File::isFile);
        for (File f : files) {
            String rel = distRoot.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (rel.contains("..")) {
                log.warn("跳过非法路径: {}", rel);
                continue;
            }
            String key = keyPrefix + rel;
            try {
                cosManager.uploadFile(key, f);
                count++;
            } catch (Exception e) {
                log.warn("上传失败 key={} msg={}", key, e.getMessage());
            }
        }
        return count;
    }

    private Path projectRoot(Long appId) {
        String dir = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, dir).toAbsolutePath().normalize();
    }
}
