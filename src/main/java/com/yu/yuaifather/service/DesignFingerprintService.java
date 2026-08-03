package com.yu.yuaifather.service;

import com.mybatisflex.core.service.IService;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
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
     * 将一条 LLM 动态合成的设计规范作为候选入库（覆盖度优化 阶段二）。
     * <p>
     * 与 {@link #saveCandidate} 的差异：
     * <ul>
     *   <li>{@code sourceType} 固定为 {@code DYNAMIC_SYNTHESIS}；</li>
     *   <li>confidence 人为压低（合成无页面证据支撑），不参与客观公式计算；</li>
     *   <li>schema 校验 / signature 去重 / CANDIDATE 门禁完全一致，人工审核 APPROVED 后才参与召回。</li>
     * </ul>
     *
     * @param dto 动态合成的六层规范
     * @return 入库后的实体；schema 不合法或命中重复时返回 {@code null}
     */
    DesignFingerprint saveSynthesizedCandidate(DesignFingerprintDTO dto);

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
     * 按 profile 查询 APPROVED 指纹（供阶段三结构化检索 STRUCTURAL_3A 通道使用）。
     * <p>
     * 权限口径（见修复计划 02）：仅返回全局公共指纹（{@code visibility=PUBLIC} 或历史遗留未显式设置
     * {@code visibility} 的精选来源），以及 {@code currentUserId} 本人的私有指纹（{@code visibility=PRIVATE}
     * 且 {@code ownerUserId=currentUserId}），杜绝跨用户私有指纹越权泄露。
     *
     * @param styleProfile  风格画像
     * @param limit         最大条数
     * @param currentUserId 当前请求用户 ID；为 {@code null} 时仅返回公共指纹（不做私有指纹召回），
     *                       仅限内部系统级调用使用，业务侵入点必须传入真实用户 ID
     */
    List<DesignFingerprint> listApproved(String styleProfile, int limit, Long currentUserId);

    /**
     * 向后兼容重载：不传用户上下文，等价于 {@code listApproved(styleProfile, limit, null)}，
     * 仅返回全局公共指纹，不召回任何私有指纹。
     */
    default List<DesignFingerprint> listApproved(String styleProfile, int limit) {
        return listApproved(styleProfile, limit, null);
    }
}
