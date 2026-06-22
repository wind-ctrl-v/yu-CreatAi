package com.yu.yuaifather.worker;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.agent.workers.DesignExtractorAgent;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.service.AppService;
import com.yu.yuaifather.service.DesignFingerprintService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 精选应用指纹爬取调度器（方案 B：定时自动）。
 * <p>
 * 每隔固定时间从 MySQL 拉取已部署的精选应用（priority=99），抓取其页面 HTML，
 * 经 {@link DesignExtractorAgent} 提炼六层指纹，存入 {@code design_fingerprint} 表（status=CANDIDATE）。
 * <p>
 * 管理员审核通过后，指纹才会同步到 PGvector 参与 AI 代码生成的检索召回。
 *
 * <h3>开关</h3>
 * <pre>{@code
 * agent.fingerprint.crawl.enabled=true    # 启用定时爬取（默认 false）
 * agent.fingerprint.crawl.interval-ms=1800000  # 间隔毫秒（默认 30 分钟）
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.fingerprint.crawl.enabled", havingValue = "true")
public class FingerprintCrawlScheduler {

    private final AppService appService;
    private final DesignExtractorAgent extractorAgent;
    private final DesignFingerprintService fingerprintService;
    private final RestTemplate restTemplate;

    @Value("${app.deploy.base-url:http://localhost:8123/api/static}")
    private String deployBaseUrl;

    @Autowired
    public FingerprintCrawlScheduler(AppService appService,
                                     DesignExtractorAgent extractorAgent,
                                     DesignFingerprintService fingerprintService) {
        this.appService = appService;
        this.extractorAgent = extractorAgent;
        this.fingerprintService = fingerprintService;

        // 超时 30s，适合本地/内网抓取
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 定时任务：每 {@code agent.fingerprint.crawl.interval-ms} 毫秒执行一次（默认 30 分钟）。
     */
    @Scheduled(fixedDelayString = "${agent.fingerprint.crawl.interval-ms:1800000}")
    public void crawlFeaturedApps() {
        log.info("========== 精选应用指纹爬取开始 ==========");
        try {
            // 1) 查询已部署的精选应用
            List<App> featuredApps = queryFeaturedDeployedApps();
            if (CollUtil.isEmpty(featuredApps)) {
                log.info("没有待爬取的精选应用（无 priority=99 且已部署的应用）");
                return;
            }
            log.info("找到 {} 个已部署的精选应用", featuredApps.size());

            int crawled = 0, skipped = 0, failed = 0;
            for (App app : featuredApps) {
                try {
                    String url = buildAppUrl(app);
                    if (StrUtil.isBlank(url)) {
                        log.warn("应用 {} 无有效部署 URL，跳过", app.getId());
                        skipped++;
                        continue;
                    }

                    // 2) 抓取页面 HTML
                    String rawHtml = fetchHtml(url);
                    if (StrUtil.isBlank(rawHtml)) {
                        log.warn("应用 {} 页面抓取为空，跳过 url={}", app.getId(), url);
                        failed++;
                        continue;
                    }

                    // 3) LLM 提炼六层指纹
                    String sourceId = String.valueOf(app.getId());
                    DesignFingerprintDTO dto = extractorAgent.extractFromHtml(
                            rawHtml, null, sourceId);
                    if (dto == null) {
                        log.warn("应用 {} 指纹提炼失败，跳过", app.getId());
                        failed++;
                        continue;
                    }

                    // 4) 入库 CANDIDATE（saveCandidate 内部有去重逻辑）
                    fingerprintService.saveCandidate(dto, true);
                    crawled++;
                    log.info("应用 {} 指纹已入库 CANDIDATE profile={}", app.getId(), dto.getStyleProfile());

                } catch (Exception e) {
                    log.error("应用 {} 爬取异常 msg={}", app.getId(), e.getMessage());
                    failed++;
                }
            }

            log.info("========== 精选应用指纹爬取结束 成功={} 跳过={} 失败={} ==========",
                    crawled, skipped, failed);

        } catch (Exception e) {
            log.error("精选应用指纹爬取整体异常", e);
        }
    }

    /**
     * 查询 priority=99 且 deployKey 不为空的已部署精选应用。
     */
    private List<App> queryFeaturedDeployedApps() {
        QueryWrapper qw = QueryWrapper.create()
                .eq("priority", AppConstant.GOOD_APP_PRIORITY)
                .isNotNull("deployKey")
                .ne("deployKey", "");
        return appService.list(qw);
    }

    /**
     * 构建应用访问 URL（与 deployApp 中的格式一致）。
     */
    private String buildAppUrl(App app) {
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            return null;
        }
        // 格式：http://localhost:8123/api/static/vue_project_xxx/ 或 .../html_xxx/
        String url = deployBaseUrl + "/" + deployKey + "/";
        // Vue 项目默认入口是 index.html
        String codeGenType = app.getCodeGenType();
        if ("vue_project".equals(codeGenType)) {
            url += "index.html";
        }
        return url;
    }

    /**
     * 抓取页面 HTML（UTF-8 解码）。
     */
    private String fetchHtml(String url) {
        try {
            byte[] body = restTemplate.execute(url, HttpMethod.GET, null,
                    response -> response.getBody().readAllBytes());
            if (body == null || body.length == 0) {
                return null;
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("抓取页面失败 url={} msg={}", url, e.getMessage());
            return null;
        }
    }
}
