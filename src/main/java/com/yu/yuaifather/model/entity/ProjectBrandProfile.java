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
 * 项目品牌画像实体类（阶段三：项目品牌记忆）。
 * <p>
 * 每个项目有独立的品牌名、行业、调性、颜色、排版等设计约束，
 * 生成时优先级高于用户长期偏好，低于当前 prompt 明确需求。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("project_brand_profile")
public class ProjectBrandProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    @Column("projectId")
    private Long projectId;

    @Column("brandName")
    private String brandName;

    private String industry;

    @Column("targetAudience")
    private String targetAudience;

    @Column("brandTone")
    private String brandTone;

    @Column("brandColors")
    private String brandColors;

    @Column("typographyPreference")
    private String typographyPreference;

    @Column("layoutPreference")
    private String layoutPreference;

    @Column("componentPreference")
    private String componentPreference;

    @Column("designGuidelines")
    private String designGuidelines;

    @Column("forbiddenRules")
    private String forbiddenRules;

    @Column("profileSummary")
    private String profileSummary;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
