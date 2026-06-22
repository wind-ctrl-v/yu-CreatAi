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
 * RAG 调用追踪实体类（阶段一：用户偏好闭环）。
 * <p>
 * 记录每次生成时 RAG 检索的完整链路：
 * 检索结果 → 重排结果 → 注入条目，用于调试和评估。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_rag_trace")
public class GenerationRagTrace implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("generationId")
    private Long generationId;

    @Column("userId")
    private Long userId;

    @Column("projectId")
    private Long projectId;

    @Column("queryText")
    private String queryText;

    @Column("queryTags")
    private String queryTags;

    @Column("styleProfile")
    private String styleProfile;

    @Column("retrievedItems")
    private String retrievedItems;

    @Column("rerankedItems")
    private String rerankedItems;

    @Column("injectedItems")
    private String injectedItems;

    @Column("retrieveChannelStats")
    private String retrieveChannelStats;

    @Column("minSimilarity")
    private Double minSimilarity;

    @Column("topSimilarity")
    private Double topSimilarity;

    @Column("usedUserProfile")
    private Boolean usedUserProfile;

    @Column("criticResult")
    private String criticResult;

    @Column("createTime")
    private LocalDateTime createTime;
}
