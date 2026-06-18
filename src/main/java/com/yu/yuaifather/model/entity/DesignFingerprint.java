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
 * 设计指纹 实体类（阶段二「后验证据」持久化，见计划 2.7）。
 * <p>
 * 列名沿用本项目「驼峰 + @Column 显式映射」约定（非 snake_case），主键用雪花 ID，
 * 逻辑删除沿用 {@code isDelete} 注解方式。
 * <p>
 * 完整六层指纹以 JSON 文本存于 {@link #fingerprintJson}；
 * {@code styleProfile / status / signature / confidence / priority} 等抽出为列，便于阶段三结构化检索。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("design_fingerprint")
public class DesignFingerprint implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 风格画像：startup / enterprise / ... / default
     */
    @Column("styleProfile")
    private String styleProfile;

    /**
     * schema 版本，例如 v1
     */
    @Column("schemaVersion")
    private String schemaVersion;

    /**
     * 来源类型：EXTRACTED_FINGERPRINT 等
     */
    @Column("sourceType")
    private String sourceType;

    /**
     * 来源标识：页面 ID / 抓取批次号
     */
    @Column("sourceId")
    private String sourceId;

    /**
     * 治理状态：CANDIDATE / APPROVED / REJECTED / ARCHIVED
     */
    private String status;

    /**
     * 去重签名：styleProfile + layout.skeleton + token signature + components 的稳定哈希
     */
    private String signature;

    /**
     * 置信度 0~1（由 FingerprintParser 客观计算）
     */
    private Double confidence;

    /**
     * 优先级，越高越优先（指纹默认低于人工静态规范基线）
     */
    private Integer priority;

    /**
     * 调性关键词，逗号分隔（冗余列，便于软匹配）
     */
    private String tone;

    /**
     * 完整六层指纹 JSON 文本（审计与投影用）
     */
    @Column("fingerprintJson")
    private String fingerprintJson;

    /**
     * 审核备注（拒绝/归档原因等，可空）
     */
    @Column("reviewRemark")
    private String reviewRemark;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
