package com.yu.yuaifather.service;

import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
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
 * 用户私有指纹入库服务（阶段二：用户私有指纹池）。
 * <p>
 * 用户保存/导出页面后触发，读取 generated_page 的 html/css，
 * 用表层特征提取构造 DesignFingerprintDTO，以 USER_PRIVATE 身份入库，
 * 并通过 VectorSyncWorker 异步同步到 PGvector。
 */
@Slf4j
@Service
public class UserFingerprintIngestService {

    /** 用户私有指纹来源类型。 */
    private static final String SOURCE_USER_PRIVATE = "USER_PRIVATE_FINGERPRINT";
    /** 用户私有指纹默认优先级（低于全局 30）。 */
    private static final int USER_PRIVATE_PRIORITY = 25;
    /** 用户私有指纹质量门控阈值。 */
    private static final double USER_PRIVATE_CONFIDENCE_GATE = 0.65;

    @Value("${agent.fingerprint.user-ingest.enabled:false}")
    private boolean userIngestEnabled;

    private final GeneratedPageMapper generatedPageMapper;
    private final DesignFingerprintService designFingerprintService;

    @Autowired
    public UserFingerprintIngestService(GeneratedPageMapper generatedPageMapper,
                                         DesignFingerprintService designFingerprintService) {
        this.generatedPageMapper = generatedPageMapper;
        this.designFingerprintService = designFingerprintService;
    }

    /**
     * 异步处理用户保存/导出的页面，提取私有指纹入库。
     * <p>
     * 流程：
     * <ol>
     *   <li>读取 generated_page 的 html/css</li>
     *   <li>构造简要 DesignFingerprintDTO（非 LLM 提取，基于表层特征）</li>
     *   <li>质量门控检查</li>
     *   <li>入库为 USER 私有指纹，自动 APPROVED</li>
     * </ol>
     * <p>
     * 注意：完整 LLM 提炼需要在后续版本中调用 DesignExtractorAgent，
     * 当前版本使用基于 HTML/CSS 的表层特征提取快速上线。
     *
     * @param pageId generated_page.id
     * @param userId 用户ID
     * @param projectId 项目ID（可空）
     */
    public void ingestUserFingerprint(Long pageId, Long userId, Long projectId) {
        if (!userIngestEnabled || pageId == null) {
            return;
        }

        // 异步执行，不阻塞主流程
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
                log.warn("页面无 HTML 内容，跳过私有指纹提取 pageId={}", pageId);
                return;
            }

            // 构造简易 DesignFingerprintDTO（基于表层特征）
            DesignFingerprintDTO dto = buildSurfaceFingerprint(page);
            if (dto == null) {
                log.warn("无法提取有效设计指纹 pageId={}", pageId);
                return;
            }

            // 质量门控
            if (dto.getConfidence() != null && dto.getConfidence() < USER_PRIVATE_CONFIDENCE_GATE) {
                log.warn("私有指纹质量不足 pageId={} confidence={}", pageId, dto.getConfidence());
                return;
            }

            // 入库为私有指纹
            DesignFingerprint saved = saveAsUserPrivate(dto, userId, projectId, pageId);
            if (saved != null) {
                log.info("用户私有指纹入库成功 pageId={} fingerprintId={} styleProfile={}",
                        pageId, saved.getId(), dto.getStyleProfile());
            }
        } catch (Exception e) {
            log.error("用户私有指纹入库异常 pageId={} userId={}", pageId, userId, e);
        }
    }

    /**
     * 基于页面表层特征构造简易设计指纹。
     * <p>
     * 当前为快速上线版本，后续可调用 DesignExtractorAgent 做 LLM 深度提炼。
     */
    private DesignFingerprintDTO buildSurfaceFingerprint(GeneratedPage page) {
        try {
            DesignFingerprintDTO.Tokens tokens = new DesignFingerprintDTO.Tokens();
            String html = page.getHtml();
            String css = page.getCss() != null ? page.getCss() : "";

            // 从 CSS 中提取颜色
            StringBuilder colorRatio = new StringBuilder();
            if (css.toLowerCase().contains("#00") || css.toLowerCase().contains("blue")) colorRatio.append("蓝");
            if (css.toLowerCase().contains("#f") || css.toLowerCase().contains("red")) colorRatio.append("红");
            if (css.toLowerCase().contains("orange")) colorRatio.append("橙");
            if (css.toLowerCase().contains("purple") || css.toLowerCase().contains("#7c3aed")) colorRatio.append("紫");
            if (css.toLowerCase().contains("green") || css.toLowerCase().contains("teal")) colorRatio.append("绿");
            if (css.toLowerCase().contains("dark") || css.toLowerCase().contains("#111")) colorRatio.append("深色");
            if (colorRatio.length() == 0) colorRatio.append("默认");
            tokens.setColorRatio(colorRatio.toString());

            // 从 CSS 提取圆角
            if (css.contains("radius:") || css.contains("border-radius")) {
                if (css.contains("50%") || css.contains("999")) {
                    tokens.setRadii("large");
                } else if (css.contains("0") || css.contains("none")) {
                    tokens.setRadii("none");
                } else {
                    tokens.setRadii("medium");
                }
            } else {
                tokens.setRadii("medium");
            }

            // 从 CSS 提取阴影
            if (css.contains("box-shadow") || css.contains("shadow")) {
                tokens.setShadow("medium");
            } else {
                tokens.setShadow("light");
            }

            // 布局骨架（从 HTML 结构推断）
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

            // 风格画像
            String styleProfile = page.getStyleProfile() != null ? page.getStyleProfile() : "default";

            // 置信度（简化版，基于信息丰富度）
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

    /**
     * 以 USER 私有身份入库指纹。
     */
    private DesignFingerprint saveAsUserPrivate(DesignFingerprintDTO dto, Long userId,
                                                  Long projectId, Long sourcePageId) {
        if (dto == null || userId == null) {
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            DesignFingerprint entity = DesignFingerprint.builder()
                    .styleProfile(dto.getStyleProfile())
                    .schemaVersion(dto.getSchemaVersion())
                    .sourceType(SOURCE_USER_PRIVATE)
                    .sourceId(sourcePageId != null ? String.valueOf(sourcePageId) : null)
                    .status(FingerprintStatus.APPROVED.getValue())  // 用户私有指纹自动 APPROVED
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
            log.info("用户私有指纹直接入库 id={} userId={} profile={}",
                    entity.getId(), userId, dto.getStyleProfile());
            return entity;
        } catch (Exception e) {
            log.error("用户私有指纹入库失败 userId={}", userId, e);
            return null;
        }
    }
}
