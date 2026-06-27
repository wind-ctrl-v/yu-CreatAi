package com.yu.yuaifather.service;

import com.yu.yuaifather.service.design.EmbeddingService;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 向量同步服务：绑定指纹生命周期到 PGvector。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.worker.VectorSyncWorker}，请使用 VectorSyncWorker 替代。
 */
@Deprecated
@Service
@ConditionalOnBean({EmbeddingService.class, DesignFingerprintVectorRepository.class})
@Slf4j
public class VectorSyncService {

    private final DesignFingerprintVectorRepository vectorRepo;
    private final EmbeddingService embeddingService;

    public VectorSyncService(DesignFingerprintVectorRepository vectorRepo,
                             EmbeddingService embeddingService) {
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
    }

    /**
     * 指纹 APPROVED 时同步向量到 PGvector。
     * <p>
     * 流程：解析 JSON → 构造语义文本 → 生成 embedding → UPSERT 入库。
     * 任一步骤失败仅 log.error，不抛异常。
     *
     * @param fingerprint MySQL 中的完整 DesignFingerprint 实体
     */
    public void syncOnApprove(DesignFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.getId() == null) {
            return;
        }
        try {
            DesignFingerprintDTO dto = JsonExtractor.parse(
                    fingerprint.getFingerprintJson(), DesignFingerprintDTO.class);
            String text = embeddingService.buildSemanticText(dto);
            if (text.isBlank()) {
                log.warn("语义文本为空，跳过向量同步 fingerprintId={}", fingerprint.getId());
                return;
            }
            float[] embedding = embeddingService.embed(text);
            if (embedding.length == 0) {
                log.warn("embedding 结果为空，跳过向量同步 fingerprintId={}", fingerprint.getId());
                return;
            }
            vectorRepo.upsert(
                    fingerprint.getId(),
                    fingerprint.getStyleProfile(),
                    fingerprint.getConfidence(),
                    fingerprint.getPriority(),
                    text,
                    embedding,
                    fingerprint.getSourceScope(),
                    fingerprint.getOwnerUserId(),
                    fingerprint.getProjectId(),
                    fingerprint.getVisibility(),
                    fingerprint.getSourceType());
            log.info("向量同步成功 fingerprintId={} profile={} dim={}",
                    fingerprint.getId(), fingerprint.getStyleProfile(), embedding.length);
        } catch (Exception e) {
            // 向量同步失败不影响 MySQL 状态
            log.error("向量同步失败 fingerprintId={} profile={}",
                    fingerprint.getId(), fingerprint.getStyleProfile(), e);
        }
    }

    /**
     * 指纹 REJECTED 或 ARCHIVED 时标记向量失效。
     *
     * @param fingerprintId MySQL 指纹 ID
     */
    public void deactivateOnRejectOrArchive(Long fingerprintId) {
        if (fingerprintId == null) {
            return;
        }
        try {
            vectorRepo.deactivate(fingerprintId);
        } catch (Exception e) {
            log.error("向量失效标记失败 fingerprintId={}", fingerprintId, e);
        }
    }
}
