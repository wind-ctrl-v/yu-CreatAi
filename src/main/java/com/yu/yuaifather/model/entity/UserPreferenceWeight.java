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
 * 用户偏好权重实体类（阶段一：用户偏好闭环）。
 * <p>
 * 精细化存储用户对具体风格/颜色/布局/组件的偏好权重，
 * weight 正值为喜欢，负值为反感，0 为中性。
 * (userId, preferenceType, preferenceKey) 联合唯一。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_preference_weight")
public class UserPreferenceWeight implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    @Column("preferenceType")
    private String preferenceType;

    @Column("preferenceKey")
    private String preferenceKey;

    private Double weight;

    @Column("evidenceCount")
    private Integer evidenceCount;

    @Column("lastEvidenceAt")
    private LocalDateTime lastEvidenceAt;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
