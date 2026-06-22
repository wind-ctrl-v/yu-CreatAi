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
 * 用户设计画像实体类（阶段一：用户偏好闭环）。
 * <p>
 * 存储用户长期设计偏好，包含喜欢/不喜欢的风格、颜色、布局、组件、交互模式。
 * JSON 字段使用 MySQL JSON 类型存储。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_design_profile")
public class UserDesignProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    @Column("profileSummary")
    private String profileSummary;

    @Column("preferredStyles")
    private String preferredStyles;

    @Column("dislikedStyles")
    private String dislikedStyles;

    @Column("preferredColors")
    private String preferredColors;

    @Column("dislikedColors")
    private String dislikedColors;

    @Column("preferredLayouts")
    private String preferredLayouts;

    @Column("dislikedLayouts")
    private String dislikedLayouts;

    @Column("preferredComponents")
    private String preferredComponents;

    @Column("dislikedComponents")
    private String dislikedComponents;

    @Column("preferredInteractions")
    private String preferredInteractions;

    @Column("dislikedInteractions")
    private String dislikedInteractions;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
