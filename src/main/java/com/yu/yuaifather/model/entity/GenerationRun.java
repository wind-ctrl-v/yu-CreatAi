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
 * 生成运行记录实体。沉淀每次生成的链路 / 模式 / 降级 / 耗时。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_run")
public class GenerationRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /** 生成全局追踪 ID（贯穿各 attempt）。 */
    @Column("generation_id")
    private String generationId;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    /** 前端请求模式（不可信意图）。 */
    @Column("requested_mode")
    private String requestedMode;

    /** 服务端最终模式。 */
    @Column("final_mode")
    private String finalMode;

    /** 实际成功链路：V2/V1/CLASSIC。 */
    @Column("pipeline_type")
    private String pipelineType;

    /** 状态：RUNNING/SUCCESS/FAILED/FALLBACK_SUCCESS。 */
    @Column("status")
    private String status;

    @Column("fallback_from")
    private String fallbackFrom;

    @Column("fallback_to")
    private String fallbackTo;

    @Column("workspace_path")
    private String workspacePath;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("finished_at")
    private LocalDateTime finishedAt;

    @Column("duration_ms")
    private Long durationMs;

    @Column("error_message")
    private String errorMessage;
}
