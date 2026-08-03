package com.yu.yuaifather.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设计检索 miss 日志实体（覆盖度优化计划 阶段一）。
 * <p>
 * 记录每次设计证据检索的 miss 现场：当三档降级（VECTOR_3B / STRUCTURAL_3A）
 * 均未召回任何指纹证据时落库，用于：
 * <ol>
 *   <li>定向补库分析：按 profile / 需求关键词聚类统计 miss 频次；</li>
 *   <li>动态合成触发：同一聚类 miss 次数达到阈值时触发 LLM 动态合成（阶段二）。</li>
 * </ol>
 * 列名沿用本项目「驼峰 + @Column 显式映射」约定，主键用雪花 ID。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("design_miss_log")
public class DesignMissLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 归一化后的风格画像（含 default）
     */
    @Column("styleProfile")
    private String styleProfile;

    /**
     * ArchitectAgent 原始识别值（归一化前，可空）
     */
    @Column("rawProfile")
    private String rawProfile;

    /**
     * 本次检索实际使用的查询文本
     */
    @Column("queryText")
    private String queryText;

    /**
     * 需求关键词，逗号分隔
     */
    @Column("queryTags")
    private String queryTags;

    /**
     * 最终召回通道：STATIC_FALLBACK / VECTOR_3B / STRUCTURAL_3A
     */
    private String channel;

    /**
     * 向量通道最高相似度（未走向量通道为 null）
     */
    @Column("maxSimilarity")
    private Double maxSimilarity;

    /**
     * 用户 id（可空）
     */
    @Column("userId")
    private Long userId;

    /**
     * 应用 id（可空）
     */
    @Column("appId")
    private Long appId;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;
}
