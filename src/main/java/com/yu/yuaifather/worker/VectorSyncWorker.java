package com.yu.yuaifather.worker;

import com.yu.yuaifather.service.design.EmbeddingService;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.service.DesignFingerprintVectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * VectorSyncWorker：向量同步异步 Worker。
 * <p>
 * 从 VectorSyncService 改名重构而来。
 * <p>
 * 原则：向量同步失败不阻塞业务流程（MySQL 状态为主，PG 为辅助索引）。
 */
@Service
@ConditionalOnBean(EmbeddingService.class)
@Slf4j
public class VectorSyncWorker {

    private final DesignFingerprintVectorRepository vectorRepo;
    private final EmbeddingService embeddingService;

    public VectorSyncWorker(DesignFingerprintVectorRepository vectorRepo,
                             EmbeddingService embeddingService) {
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
    }

    public void syncOnApprove(DesignFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.getId() == null) return;
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
                log.warn("embedding 结果为空 fingerprintId={}", fingerprint.getId());
                return;
            }
            vectorRepo.upsert(fingerprint.getId(), fingerprint.getStyleProfile(),
                    fingerprint.getConfidence(), fingerprint.getPriority(), text, embedding,
                    fingerprint.getSourceScope(), fingerprint.getOwnerUserId(),
                    fingerprint.getProjectId(), fingerprint.getVisibility(), fingerprint.getSourceType());
            log.info("向量同步成功 fingerprintId={} profile={} dim={}",
                    fingerprint.getId(), fingerprint.getStyleProfile(), embedding.length);
        } catch (Exception e) {
            log.error("向量同步失败 fingerprintId={} profile={}",
                    fingerprint.getId(), fingerprint.getStyleProfile(), e);
        }
    }

    public void deactivateOnRejectOrArchive(Long fingerprintId) {
        if (fingerprintId == null) return;
        try {
            vectorRepo.deactivate(fingerprintId);
        } catch (Exception e) {
            log.error("向量失效标记失败 fingerprintId={}", fingerprintId, e);
        }
    }
}
