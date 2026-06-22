package com.yu.yuaifather.agent.workers;

import cn.hutool.core.io.FileUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.ai.core.bulider.VueProjectBuilder;
import com.yu.yuaifather.config.CosClientConfig;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.manager.CosManager;
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
 * DeployAgent：执行 npm install → npm run build → 将 dist 上传 COS。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.tool.system.DeployTool}，请使用 DeployTool 替代。
 */
@Deprecated
@Slf4j
@Component
public class DeployAgent implements Agent {

    public static final String AGENT_NAME = "DeployAgent";

    private final VueProjectBuilder vueProjectBuilder;
    private final CosManager cosManager;
    private final CosClientConfig cosClientConfig;

    @Autowired
    public DeployAgent(VueProjectBuilder vueProjectBuilder,
                       CosManager cosManager,
                       CosClientConfig cosClientConfig) {
        this.vueProjectBuilder = vueProjectBuilder;
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "构建并部署中"));
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
                        .agentName(AGENT_NAME)
                        .content("部署完成 files=" + count + " url=" + url)
                        .data(url)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("DeployAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "部署失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 递归上传 dist 目录文件，返回上传文件数。 */
    private int uploadRecursive(File distRoot, File current, String keyPrefix) {
        int count = 0;
        Collection<File> files = FileUtil.loopFiles(current, File::isFile);
        for (File f : files) {
            String rel = distRoot.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            // 防穿越：rel 必须不包含 ..
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
