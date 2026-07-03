package com.yu.yuaifather.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yu.yuaifather.service.design.FingerprintParser;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.mapper.DesignFingerprintMapper;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.enums.FingerprintStatus;
import com.yu.yuaifather.service.DesignFingerprintService;
import com.yu.yuaifather.worker.VectorSyncWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 设计指纹 服务实现（阶段二治理：入库 / 去重 / 状态流转 / 多样性配额）。
 *
 * @author yu
 */
@Slf4j
@Service
public class DesignFingerprintServiceImpl
        extends ServiceImpl<DesignFingerprintMapper, DesignFingerprint>
        implements DesignFingerprintService {

    /** 真实精选页面来源。 */
    private static final String SOURCE_CURATED = "EXTRACTED_FINGERPRINT";
    /** 系统自产页面反哺来源。 */
    private static final String SOURCE_SELF_FED = "SELF_FED_FINGERPRINT";

    /** 置信度门槛：低于此值不得晋升 APPROVED（不参与召回，见 2.5）。 */
    private static final double CONFIDENCE_GATE = 0.7;
    /** 每个 profile 中自产反哺指纹在 APPROVED 池的占比上限（多样性防同质化，见 2.5 / 评审 #7）。 */
    private static final double MAX_SELF_FED_RATIO = 0.3;
    /** 指纹默认优先级（低于人工静态规范基线 50）。 */
    private static final int FINGERPRINT_PRIORITY = 30;

    private final FingerprintParser fingerprintParser;
    private final VectorSyncWorker vectorSyncWorker;

    @Autowired
    public DesignFingerprintServiceImpl(FingerprintParser fingerprintParser,
                                        @Autowired(required = false) VectorSyncWorker vectorSyncWorker) {
        this.fingerprintParser = fingerprintParser;
        this.vectorSyncWorker = vectorSyncWorker;
    }

    @Override
    public DesignFingerprint saveCandidate(DesignFingerprintDTO dto, boolean selfGenerated) {
        if (dto == null) {
            return null;
        }
        // 版本路由：无法识别的高版本跳过，不报错（呼应 2.2.1）
        if (!fingerprintParser.isSupportedVersion(dto)) {
            log.warn("指纹 schema 版本不支持，跳过入库 version={}", dto.getSchemaVersion());
            return null;
        }
        // schema 校验
        FingerprintParser.Validation validation = fingerprintParser.validate(dto);
        if (!validation.isValid()) {
            log.warn("指纹 schema 校验不通过，拒绝入库 errors={}", validation.getErrors());
            return null;
        }
        // 置信度（自产反哺来源可信度低）
        double confidence = fingerprintParser.computeConfidence(dto, !selfGenerated);
        String signature = fingerprintParser.signature(dto);

        // 去重：相同签名且未被拒绝/归档的视为重复
        if (existsActiveSignature(signature)) {
            log.info("指纹命中重复签名，跳过入库 signature={} profile={}", signature, dto.getStyleProfile());
            return null;
        }

        String sourceType = selfGenerated ? SOURCE_SELF_FED : SOURCE_CURATED;
        LocalDateTime now = LocalDateTime.now();
        DesignFingerprint entity = DesignFingerprint.builder()
                .styleProfile(dto.getStyleProfile())
                .schemaVersion(dto.getSchemaVersion())
                .sourceType(sourceType)
                .sourceId(dto.getSourceId())
                .status(FingerprintStatus.CANDIDATE.getValue())
                .signature(signature)
                .confidence(confidence)
                .priority(FINGERPRINT_PRIORITY)
                .tone(dto.getTone() == null ? null : String.join(",", dto.getTone()))
                .fingerprintJson(JSONUtil.toJsonStr(dto))
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
        this.save(entity);
        log.info("指纹入库为候选 id={} profile={} confidence={} selfGenerated={}",
                entity.getId(), entity.getStyleProfile(), confidence, selfGenerated);
        return entity;
    }

    @Override
    public boolean approve(Long id) {
        DesignFingerprint fp = getById(id);
        if (fp == null) {
            return false;
        }
        if (fp.getConfidence() != null && fp.getConfidence() < CONFIDENCE_GATE) {
            log.warn("指纹置信度低于门槛，拒绝晋升 APPROVED id={} confidence={}", id, fp.getConfidence());
            return false;
        }
        // 自产反哺配额校验：晋升后自产占比不得超过上限
        if (SOURCE_SELF_FED.equals(fp.getSourceType()) && exceedsSelfFedQuotaAfterApprove(fp.getStyleProfile())) {
            log.warn("自产反哺指纹超出多样性配额，拒绝晋升 id={} profile={}", id, fp.getStyleProfile());
            return false;
        }
        return updateStatus(fp, FingerprintStatus.APPROVED, null);
    }

    @Override
    public boolean reject(Long id, String remark) {
        DesignFingerprint fp = getById(id);
        if (fp == null) {
            return false;
        }
        boolean ok = updateStatus(fp, FingerprintStatus.REJECTED, remark);
        if (ok && vectorSyncWorker != null) {
            vectorSyncWorker.deactivateOnRejectOrArchive(id);
        }
        return ok;
    }

    @Override
    public boolean archive(Long id, String remark) {
        DesignFingerprint fp = getById(id);
        if (fp == null) {
            return false;
        }
        boolean ok = updateStatus(fp, FingerprintStatus.ARCHIVED, remark);
        if (ok && vectorSyncWorker != null) {
            vectorSyncWorker.deactivateOnRejectOrArchive(id);
        }
        return ok;
    }

    @Override
    public List<DesignFingerprint> listApproved(String styleProfile, int limit, Long currentUserId) {
        QueryWrapper qw = QueryWrapper.create()
                .eq("status", FingerprintStatus.APPROVED.getValue());
        if (styleProfile != null && !styleProfile.isBlank()) {
            qw.eq("styleProfile", styleProfile);
        }
        // ★ 权限过滤（修复计划 02）：GLOBAL/PUBLIC 公共指纹 或当前用户私有指纹
        // 使用 QueryWrapper.and(String, Object...) 的 "?" 占位符参数绑定，避免将 currentUserId
        // 直接拼接进 SQL 字符串（安全修复，见 docs/rag-fix-plans/07-*.md）。
        if (currentUserId != null) {
            qw.and("(visibility IS NULL OR visibility = 'PUBLIC' OR (visibility = 'PRIVATE' AND owner_user_id = ?))",
                    currentUserId);
        } else {
            qw.and("(visibility IS NULL OR visibility = 'PUBLIC')");
        }
        List<DesignFingerprint> list = this.list(qw);
        if (CollUtil.isEmpty(list)) {
            return List.of();
        }
        // 排序：priority DESC → confidence DESC → updateTime DESC（与 3.5 合并排序口径一致）
        list.sort(Comparator
                .comparing((DesignFingerprint f) -> f.getPriority() == null ? 0 : f.getPriority(), Comparator.reverseOrder())
                .thenComparing(f -> f.getConfidence() == null ? 0d : f.getConfidence(), Comparator.reverseOrder())
                .thenComparing(f -> f.getUpdateTime() == null ? LocalDateTime.MIN : f.getUpdateTime(), Comparator.reverseOrder()));
        int n = Math.max(0, limit);
        return n > 0 && list.size() > n ? list.subList(0, n) : list;
    }

    // =================== 内部辅助 ===================

    private boolean existsActiveSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        QueryWrapper qw = QueryWrapper.create()
                .eq("signature", signature)
                .in("status", FingerprintStatus.CANDIDATE.getValue(), FingerprintStatus.APPROVED.getValue());
        return this.count(qw) > 0;
    }

    /**
     * 校验在某 profile 下晋升一条自产反哺后，自产占比是否会超过上限。
     */
    private boolean exceedsSelfFedQuotaAfterApprove(String styleProfile) {
        QueryWrapper approvedQw = QueryWrapper.create()
                .eq("status", FingerprintStatus.APPROVED.getValue())
                .eq("styleProfile", styleProfile);
        long approvedTotal = this.count(approvedQw);

        QueryWrapper selfFedQw = QueryWrapper.create()
                .eq("status", FingerprintStatus.APPROVED.getValue())
                .eq("styleProfile", styleProfile)
                .eq("sourceType", SOURCE_SELF_FED);
        long selfFedApproved = this.count(selfFedQw);

        // 晋升后：自产 +1，总数 +1
        double ratioAfter = (double) (selfFedApproved + 1) / (approvedTotal + 1);
        return ratioAfter > MAX_SELF_FED_RATIO;
    }

    private boolean updateStatus(DesignFingerprint fp, FingerprintStatus status, String remark) {
        fp.setStatus(status.getValue());
        fp.setUpdateTime(LocalDateTime.now());
        if (remark != null) {
            fp.setReviewRemark(remark);
        }
        boolean ok = this.updateById(fp);
        if (ok) {
            log.info("指纹状态流转 id={} -> {} remark={}", fp.getId(), status.getValue(), remark);
            // APPROVED 时触发向量同步（失败不影响 MySQL 事务）
            if (status == FingerprintStatus.APPROVED && vectorSyncWorker != null) {
                vectorSyncWorker.syncOnApprove(fp);
            }
        }
        return ok;
    }
}
