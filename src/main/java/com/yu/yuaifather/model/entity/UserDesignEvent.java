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
 * 用户设计行为事件实体类（阶段一：用户偏好闭环）。
 * <p>
 * 只写不删，记录用户在页面生成过程中的完整行为链路，
 * 用于后续规则驱动偏好更新。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_design_event")
public class UserDesignEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    @Column("projectId")
    private Long projectId;

    @Column("generationId")
    private Long generationId;

    @Column("pageId")
    private Long pageId;

    @Column("eventType")
    private String eventType;

    @Column("targetType")
    private String targetType;

    @Column("targetId")
    private Long targetId;

    @Column("metadata")
    private String metadata;

    @Column("createTime")
    private LocalDateTime createTime;
}
