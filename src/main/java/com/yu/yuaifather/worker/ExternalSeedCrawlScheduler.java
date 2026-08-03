package com.yu.yuaifather.worker;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.enums.FingerprintStatus;
import com.yu.yuaifather.service.DesignFingerprintService;
import com.yu.yuaifather.service.design.DesignExtractorAgent;
import com.yu.yuaifather.service.design.RenderedHtmlFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ExternalSeedCrawlScheduler：外部种子页面指纹爬取调度器（覆盖度优化 阶段三）。
 * <p>
 * 打破「内部精选应用自复制」闭环：从配置的外部优秀设计站点种子 URL 清单抓取页面，
 * 经 {@link DesignExtractorAgent} 提炼六层指纹，以 {@code EXTRACTED_FINGERPRINT}
 * （真实精选页面来源）入库 CANDIDATE，人工审核 APPROVED 后参与召回。
 *
 * <h3>合规与安全</h3>
 * <ul>
 *   <li>SSRF：所有 URL 经 {@code SafeUrlValidator}（仅公网 HTTP/HTTPS），
 *       渲染模式加载后二次校验落地 URL；</li>
 *   <li>礼貌策略：带可识别爬虫 UA、逐 URL 抓取间隔（默认 5s）、按天级定时；</li>
 *   <li>去重：{@code sourceId=URL} 已存在（CANDIDATE/APPROVED 态）则跳过，
 *       避免重复 LLM 提炼开销；指纹级 signature 去重由 saveCandidate 兜底。</li>
 * </ul>
 *
 * <h3>开关与配置</h3>
 * <pre>{@code
 * agent.fingerprint.external-crawl.enabled=true            # 启用（默认 false）
 * agent.fingerprint.external-crawl.interval-ms=86400000    # 调度间隔（默认 1 天）
 * agent.fingerprint.external-crawl.urls=https://site1,https://site2  # 种子清单（逗号分隔）
 * agent.fingerprint.external-crawl.render-enabled=true     # SPA 渲染抓取（默认 true，失败降级静态）
 * agent.fingerprint.external-crawl.request-interval-ms=5000  # 单 URL 抓取间隔（默认 5s）
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.fingerprint.external-crawl.enabled", havingValue = "true")
public class ExternalSeedCrawlScheduler {

    private final DesignExtractorAgent extractorAgent;
    private final DesignFingerprintService fingerprintService;
    private final RenderedHtmlFetcher htmlFetcher;

    @Value("${agent.fingerprint.external-crawl.urls:}")
    private String seedUrls;

    @Value("${agent.fingerprint.external-crawl.render-enabled:true}")
    private boolean renderEnabled;

    @Value("${agent.fingerprint.external-crawl.request-interval-ms:5000}")
    private long requestIntervalMs;

    @Autowired
    public ExternalSeedCrawlScheduler(DesignExtractorAgent extractorAgent,
                                      DesignFingerprintService fingerprintService,
                                      RenderedHtmlFetcher htmlFetcher) {
        this.extractorAgent = extractorAgent;
        this.fingerprintService = fingerprintService;
        this.htmlFetcher = htmlFetcher;
    }

    /**
     * 定时任务：每 {@code agent.fingerprint.external-crawl.interval-ms} 毫秒执行一次（默认 1 天）。
     */
    @Scheduled(fixedDelayString = "${agent.fingerprint.external-crawl.interval-ms:86400000}")
    public void crawlExternalSeeds() {
        List<String> urls = parseSeedUrls();
        if (urls.isEmpty()) {
            log.info("外部种子指纹爬取：未配置种子 URL，跳过（agent.fingerprint.external-crawl.urls）");
            return;
        }
        log.info("========== 外部种子指纹爬取开始 种子数={} ==========", urls.size());
        int crawled = 0, skipped = 0, failed = 0;
        for (String url : urls) {
            try {
                // 1) sourceId 级去重：已入库（候选/已审核）则跳过，不重复消耗 LLM
                if (alreadyIngested(url)) {
                    log.info("种子已入库，跳过 url={}", url);
                    skipped++;
                    continue;
                }
                // 2) 抓取 HTML（SSRF 校验 + SPA 渲染 + 静态降级在 fetcher 内完成）
                String html = htmlFetcher.fetch(url, renderEnabled);
                if (StrUtil.isBlank(html)) {
                    log.warn("种子页面抓取为空 url={}", url);
                    failed++;
                    continue;
                }
                // 3) LLM 提炼六层指纹（sourceId=URL，便于追溯与去重）
                DesignFingerprintDTO dto = extractorAgent.extractFromHtml(html, null, url);
                if (dto == null) {
                    log.warn("种子页面指纹提炼失败 url={}", url);
                    failed++;
                    continue;
                }
                // 4) 入库 CANDIDATE（外部精选来源：selfGenerated=false，signature 去重兜底）
                DesignFingerprint saved = fingerprintService.saveCandidate(dto, false);
                if (saved != null) {
                    crawled++;
                    log.info("外部种子指纹入库 CANDIDATE id={} url={} profile={}",
                            saved.getId(), url, dto.getStyleProfile());
                } else {
                    skipped++;
                }
                // 礼貌策略：逐 URL 间隔
                Thread.sleep(requestIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("外部种子指纹爬取被中断");
                break;
            } catch (Exception e) {
                failed++;
                log.error("外部种子处理异常 url={} msg={}", url, e.getMessage());
            }
        }
        log.info("========== 外部种子指纹爬取结束 成功={} 跳过={} 失败={} ==========",
                crawled, skipped, failed);
    }

    /**
     * 解析配置的种子 URL 清单（逗号分隔，去空白）。
     */
    private List<String> parseSeedUrls() {
        List<String> urls = new ArrayList<>();
        if (StrUtil.isBlank(seedUrls)) {
            return urls;
        }
        for (String u : seedUrls.split(",")) {
            String url = u.trim();
            if (StrUtil.isNotBlank(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
     * 判断该 URL 是否已入库（CANDIDATE / APPROVED 态视为已摄取；
     * REJECTED / ARCHIVED 不拦截，允许运营清理后重新摄取）。
     */
    private boolean alreadyIngested(String url) {
        try {
            QueryWrapper qw = QueryWrapper.create()
                    .eq(DesignFingerprint::getSourceId, url)
                    .in(DesignFingerprint::getStatus,
                            FingerprintStatus.CANDIDATE.getValue(),
                            FingerprintStatus.APPROVED.getValue());
            return fingerprintService.count(qw) > 0;
        } catch (Exception e) {
            // 去重查询失败时按未入库处理（saveCandidate 的 signature 去重兜底，不会产脏数据）
            log.warn("种子去重查询失败，按未入库继续 url={} msg={}", url, e.getMessage());
            return false;
        }
    }
}
