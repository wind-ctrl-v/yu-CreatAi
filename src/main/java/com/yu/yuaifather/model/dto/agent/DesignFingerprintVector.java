package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PGvector 向量表 POJO（对应 {@code design_fingerprint_vector} 表）。
 * <p>
 * 与 MySQL {@code design_fingerprint} 通过 {@link #fingerprintId} 关联，
 * 仅存储向量及其元数据，完整六层指纹仍以 MySQL 为权威来源。
 * <p>
 * 不入 MyBatis-Flex，通过 {@link com.yu.yuaifather.service.DesignFingerprintVectorRepository}
 * 的 JdbcTemplate 原生 SQL 操作。
 *
 * @author yu
 * @see com.yu.yuaifather.service.DesignFingerprintVectorRepository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesignFingerprintVector {

    /** 自增主键。 */
    private Long id;

    /** 对应 MySQL {@code design_fingerprint.id}。 */
    private Long fingerprintId;

    /** 风格画像（冗余，用于硬过滤）。 */
    private String styleProfile;

    /** 治理状态：APPROVED / CANDIDATE 等（冗余）。 */
    private String status;

    /** 置信度（冗余，辅助排序）。 */
    private Double confidence;

    /** 优先级（冗余，辅助排序）。 */
    private Integer priority;

    /** 用于生成 embedding 的原始语义文本（调试/审计用）。 */
    private String embeddingText;

    /** 向量（维度与 embedding 模型一致，默认 1536）。 */
    private float[] embedding;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 是否有效（软开关，REJECTED/ARCHIVED 时置 false）。 */
    private Boolean isActive;

    /** 来源范围（阶段二新增）：GLOBAL/USER/PROJECT。 */
    private String sourceScope;

    /** 归属用户ID（阶段二新增）。 */
    private Long ownerUserId;

    /** 归属项目ID（阶段三新增）。 */
    private Long projectId;

    /** 可见性（阶段二新增）：PUBLIC/PRIVATE/TEAM。 */
    private String visibility;

    /** 来源类型（冗余）。 */
    private String sourceType;

    /** embedding 模型名称。 */
    private String embeddingModel;

    /** embedding 版本。 */
    private String embeddingVersion;

    /** 语义文本哈希（阶段二新增）。 */
    private String semanticTextHash;

    /** cosine 相似度（查询时计算，非持久化字段）。 */
    private Double similarity;
}
