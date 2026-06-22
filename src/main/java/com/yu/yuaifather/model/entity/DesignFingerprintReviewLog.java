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
 * 设计指纹审核日志实体类（阶段三：评估治理）。
 * <p>
 * 记录每次指纹审核操作，用于分析哪些来源/类型质量高、哪些常被拒绝。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("design_fingerprint_review_log")
public class DesignFingerprintReviewLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("fingerprintId")
    private Long fingerprintId;

    @Column("reviewerId")
    private Long reviewerId;

    private String action;

    private String reason;

    @Column("qualityScore")
    private Double qualityScore;

    @Column("createTime")
    private LocalDateTime createTime;
}
