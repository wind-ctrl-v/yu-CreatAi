package com.yu.yuaifather.service;

import com.mybatisflex.core.service.IService;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import com.yu.yuaifather.model.entity.DesignFingerprint;

import java.util.List;

/**
 * 设计指纹 服务层（阶段二「后验证据」治理，见计划 2.5 / 2.7）。
 * <p>
 * 统一封装指纹的入库、schema 校验、置信度计算、去重、状态流转与多样性配额，
 * 不在此引入向量检索（阶段三）。
 *
 * @author yu
 */
public interface DesignFingerprintService extends IService<DesignFingerprint> {

    /**
     * 将一条提炼指纹作为候选入库。
     * <p>
     * 流程：schema 校验 → 计算 confidence → 生成签名 → 去重检测 → 以 {@code CANDIDATE} 状态保存。
     * 新提炼结果一律先进入 {@code CANDIDATE}，不直接召回（见 2.5）。
     *
     * @param dto           六层指纹
     * @param selfGenerated 是否来自系统自产页面的反哺（影响来源可信度与多样性配额）
     * @return 入库后的实体；schema 不合法或命中重复时返回 {@code null}
     */
    DesignFingerprint saveCandidate(DesignFingerprintDTO dto, boolean selfGenerated);

    /**
     * 审核通过：CANDIDATE → APPROVED。
     * <p>对自产反哺指纹会校验多样性配额（每个 profile 中自产占比上限），超限则拒绝晋升。
     *
     * @return 是否成功晋升
     */
    boolean approve(Long id);

    /**
     * 拒绝：→ REJECTED。
     */
    boolean reject(Long id, String remark);

    /**
     * 归档：APPROVED → ARCHIVED（过期或被更优样本替代）。
     */
    boolean archive(Long id, String remark);

    /**
     * 按 profile 查询 APPROVED 指纹（供阶段三结构化检索预备）。
     *
     * @param styleProfile 风格画像
     * @param limit        最大条数
     */
    List<DesignFingerprint> listApproved(String styleProfile, int limit);
}
