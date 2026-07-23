package com.yu.yuaifather.service;

import com.yu.yuaifather.mapper.GenerationRagTraceMapper;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.entity.GenerationRagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 指纹质量反哺服务（阶段三：评估治理）。
 * <p>
 * CriticAgent 评估后，反向更新指纹的 usage_count、retrieval_success_score，
 * 并记录审核日志。
 *
 * @author yu
 */
@Slf4j
@Service
public class FingerprintFeedbackService {

    private final DesignFingerprintService designFingerprintService;
    private final GenerationRagTraceMapper ragTraceMapper;

    @Autowired
    public FingerprintFeedbackService(DesignFingerprintService designFingerprintService,
                                       GenerationRagTraceMapper ragTraceMapper) {
        this.designFingerprintService = designFingerprintService;
        this.ragTraceMapper = ragTraceMapper;
    }

    /**
     * 根据 CriticAgent 评估结果反哺指纹质量。
     * <p>
     * 更新链条：
     * <ol>
     *   <li>generated_page.qualityScore 更新</li>
     *   <li>design_fingerprint.usage_count++</li>
     *   <li>design_fingerprint.retrieval_success_score 更新</li>
     *   <li>generation_rag_trace 补充 Critic 结果</li>
     * </ol>
     *
     * @param fingerprintIds 本次注入的指纹ID列表
     * @param qualityScore   CriticAgent 评估质量分
     * @param ragTraceId     RAG 追踪记录ID
     * @param criticResult   Critic 结构化评估结果JSON
     */
    @Transactional(rollbackFor = Exception.class)
    public void feedbackFingerprintQuality(List<Long> fingerprintIds, double qualityScore,
                                            Long ragTraceId, String criticResult) {
        if (fingerprintIds == null || fingerprintIds.isEmpty()) {
            return;
        }

        for (Long fpId : fingerprintIds) {
            try {
                DesignFingerprint fp = designFingerprintService.getById(fpId);
                if (fp == null) continue;

                // 更新使用次数
                int newUsageCount = (fp.getUsageCount() == null ? 0 : fp.getUsageCount()) + 1;
                fp.setUsageCount(newUsageCount);

                // 更新检索成功评分（指数移动平均）
                double oldScore = fp.getRetrievalSuccessScore() == null ? 0.5 : fp.getRetrievalSuccessScore();
                double newScore = oldScore * 0.9 + qualityScore * 0.1;
                fp.setRetrievalSuccessScore(newScore);

                // 根据质量分更新正/负面反馈计数
                if (qualityScore >= 0.8) {
                    fp.setPositiveFeedbackCount(
                            (fp.getPositiveFeedbackCount() == null ? 0 : fp.getPositiveFeedbackCount()) + 1);
                } else if (qualityScore < 0.5) {
                    fp.setNegativeFeedbackCount(
                            (fp.getNegativeFeedbackCount() == null ? 0 : fp.getNegativeFeedbackCount()) + 1);
                }

                designFingerprintService.updateById(fp);
                log.debug("指纹质量反哺 fingerprintId={} usageCount={} retrievalScore={}",
                        fpId, newUsageCount, String.format("%.3f", newScore));
            } catch (Exception e) {
                log.warn("指纹质量反哺失败 fingerprintId={}", fpId, e);
            }
        }

        // 更新 RAG trace 中的 Critic 结果
        if (ragTraceId != null && criticResult != null) {
            try {
                GenerationRagTrace trace = ragTraceMapper.selectOneById(ragTraceId);
                if (trace != null) {
                    trace.setCriticResult(criticResult);
                    ragTraceMapper.update(trace);
                }
            } catch (Exception e) {
                log.warn("RAG trace Critic 结果更新失败 traceId={}", ragTraceId, e);
            }
        }
    }

}
