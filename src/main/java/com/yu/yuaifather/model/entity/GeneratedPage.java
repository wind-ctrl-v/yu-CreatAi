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
 * 生成页面快照实体类（阶段一：用户偏好闭环）。
 * <p>
 * 每次代码生成后落库，记录生成结果、使用的设计规范和 RAG 证据快照。
 * isSaved / isExported / isDeleted 由用户操作更新。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generated_page")
public class GeneratedPage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    @Column("projectId")
    private Long projectId;

    @Column("requirementText")
    private String requirementText;

    @Column("normalizedRequirement")
    private String normalizedRequirement;

    @Column("styleProfile")
    private String styleProfile;

    private String html;

    private String css;

    private String js;

    @Column("screenshotUrl")
    private String screenshotUrl;

    @Column("designSpecSnapshot")
    private String designSpecSnapshot;

    @Column("designEvidenceSnapshot")
    private String designEvidenceSnapshot;

    @Column("isSaved")
    private Boolean isSaved;

    @Column("isExported")
    private Boolean isExported;

    @Column("isDeleted")
    private Boolean isDeleted;

    @Column("userRating")
    private Integer userRating;

    @Column("qualityScore")
    private Double qualityScore;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
