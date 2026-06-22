package com.yu.yuaifather.worker;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import com.yu.yuaifather.mapper.GeneratedPageMapper;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.entity.GeneratedPage;
import com.yu.yuaifather.model.enums.FingerprintStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

/**
 * FingerprintIngestWorker：用户私有指纹入库异步 Worker。
 * <p>
 * 从 UserFingerprintIngestService 改名重构而来。
 * <p>
 * 用户保存/导出页面后异步触发，读取 generated_page 的 html/css/js，
 * 提取六层指纹，标记 source_scope=USER 入库。
 */
@Slf4j
@Service
public class FingerprintIngestWorker {

    private static final String SOURCE_USER_PRIVATE = "USER_PRIVATE_FINGERPRINT";
    private static final int USER_PRIVATE_PRIORITY = 25;
    private static final double USER_PRIVATE_CONFIDENCE_GATE = 0.65;

    @Value("${agent.fingerprint.user-ingest.enabled:false}")
    private boolean userIngestEnabled;

    private final GeneratedPageMapper generatedPageMapper;
    private final com.yu.yuaifather.service.DesignFingerprintService designFingerprintService;

    @Autowired
    public FingerprintIngestWorker(GeneratedPageMapper generatedPageMapper,
                                    com.yu.yuaifather.service.DesignFingerprintService designFingerprintService) {
        this.generatedPageMapper = generatedPageMapper;
        this.designFingerprintService = designFingerprintService;
    }

    public void ingestUserFingerprint(Long pageId, Long userId, Long projectId) {
        if (!userIngestEnabled || pageId == null) {
            return;
        }
        Mono.fromRunnable(() -> doIngest(pageId, userId, projectId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> log.info("用户私有指纹入库完成 pageId={}", pageId),
                        error -> log.warn("用户私有指纹入库失败 pageId={} msg={}", pageId, error.getMessage())
                );
    }

    private void doIngest(Long pageId, Long userId, Long projectId) {
        try {
            GeneratedPage page = generatedPageMapper.selectOneById(pageId);
            if (page == null) {
                log.warn("页面不存在，跳过私有指纹提取 pageId={}", pageId);
                return;
            }
            if (page.getHtml() == null || page.getHtml().isBlank()) {
                log.warn("页面无 HTML 内容 pageId={}", pageId);
                return;
            }
            DesignFingerprintDTO dto = buildSurfaceFingerprint(page);
            if (dto == null) {
                return;
            }
            if (dto.getConfidence() != null && dto.getConfidence() < USER_PRIVATE_CONFIDENCE_GATE) {
                log.warn("私有指纹质量不足 pageId={} confidence={}", pageId, dto.getConfidence());
                return;
            }
            DesignFingerprint saved = saveAsUserPrivate(dto, userId, projectId, pageId);
            if (saved != null) {
                log.info("用户私有指纹入库成功 pageId={} fingerprintId={} styleProfile={}",
                        pageId, saved.getId(), dto.getStyleProfile());
            }
        } catch (Exception e) {
            log.error("用户私有指纹入库异常 pageId={} userId={}", pageId, userId, e);
        }
    }

    private DesignFingerprintDTO buildSurfaceFingerprint(GeneratedPage page) {
        try {
            DesignFingerprintDTO.Tokens tokens = new DesignFingerprintDTO.Tokens();
            String html = page.getHtml();
            String css = page.getCss() != null ? page.getCss() : "";
            StringBuilder colorRatio = new StringBuilder();
            if (css.toLowerCase().contains("#00") || css.toLowerCase().contains("blue")) colorRatio.append("蓝");
            if (css.toLowerCase().contains("#f") || css.toLowerCase().contains("red")) colorRatio.append("红");
            if (css.toLowerCase().contains("orange")) colorRatio.append("橙");
            if (css.toLowerCase().contains("purple") || css.toLowerCase().contains("#7c3aed")) colorRatio.append("紫");
            if (css.toLowerCase().contains("green") || css.toLowerCase().contains("teal")) colorRatio.append("绿");
            if (css.toLowerCase().contains("dark") || css.toLowerCase().contains("#111")) colorRatio.append("深色");
            if (colorRatio.length() == 0) colorRatio.append("默认");
            tokens.setColorRatio(colorRatio.toString());
            if (css.contains("radius:") || css.contains("border-radius")) {
                tokens.setRadii(css.contains("50%") || css.contains("999") ? "large"
                        : css.contains("0") || css.contains("none") ? "none" : "medium");
            } else {
                tokens.setRadii("medium");
            }
            tokens.setShadow(css.contains("box-shadow") || css.contains("shadow") ? "medium" : "light");
            DesignFingerprintDTO.Layout layout = new DesignFingerprintDTO.Layout();
            StringBuilder skeleton = new StringBuilder();
            if (html.toLowerCase().contains("<header") || html.toLowerCase().contains("hero")) skeleton.append("header-hero");
            if (html.toLowerCase().contains("grid")) skeleton.append("→grid");
            if (html.toLowerCase().contains("card")) skeleton.append("→cards");
            if (html.toLowerCase().contains("footer")) skeleton.append("→footer");
            if (skeleton.length() == 0) skeleton.append("single-section");
            layout.setSkeleton(skeleton.toString());
            layout.setWhitespace("normal");
            layout.setFocus("centered");
            String styleProfile = page.getStyleProfile() != null ? page.getStyleProfile() : "default";
            double confidence = 0.6;
            if (colorRatio.length() > 3) confidence += 0.1;
            if (css.length() > 500) confidence += 0.05;
            if (html.length() > 2000) confidence += 0.05;
            DesignFingerprintDTO dto = new DesignFingerprintDTO();
            dto.setStyleProfile(styleProfile);
            dto.setSchemaVersion("v1");
            dto.setTokens(tokens);
            dto.setLayout(layout);
            dto.setConfidence(Math.min(1.0, confidence));
            return dto;
        } catch (Exception e) {
            log.warn("构造简易指纹失败 pageId={}", page.getId(), e);
            return null;
        }
    }

    private DesignFingerprint saveAsUserPrivate(DesignFingerprintDTO dto, Long userId,
                                                  Long projectId, Long sourcePageId) {
        if (dto == null || userId == null) return null;
        try {
            LocalDateTime now = LocalDateTime.now();
            DesignFingerprint entity = DesignFingerprint.builder()
                    .styleProfile(dto.getStyleProfile())
                    .schemaVersion(dto.getSchemaVersion())
                    .sourceType(SOURCE_USER_PRIVATE)
                    .sourceId(sourcePageId != null ? String.valueOf(sourcePageId) : null)
                    .status(FingerprintStatus.APPROVED.getValue())
                    .confidence(dto.getConfidence())
                    .priority(USER_PRIVATE_PRIORITY)
                    .sourceScope("USER")
                    .ownerUserId(userId)
                    .projectId(projectId)
                    .visibility("PRIVATE")
                    .sourcePageId(sourcePageId)
                    .fingerprintJson(JSONUtil.toJsonStr(dto))
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            designFingerprintService.save(entity);
            log.info("用户私有指纹直接入库 id={} userId={} profile={}", entity.getId(), userId, dto.getStyleProfile());
            return entity;
        } catch (Exception e) {
            log.error("用户私有指纹入库失败 userId={}", userId, e);
            return null;
        }
    }
}
