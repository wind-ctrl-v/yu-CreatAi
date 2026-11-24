package com.yu.yuaifather.model.vo;

import com.yu.yuaifather.observability.StepTokenUsage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次生成的分步 token 用量。给生成页在结束后回读，也给进行中的刷新。
 */
@Data
@Builder
public class GenerationTokenUsageVO {

    private String generationId;

    private Long appId;

    /** RUNNING / SUCCESS / FAILED / FALLBACK_SUCCESS。 */
    private String status;

    /** V2 / CLASSIC / INCREMENTAL。 */
    private String pipelineType;

    private long inputTokens;

    private long outputTokens;

    private long totalTokens;

    private int callCount;

    private int unknownUsageCalls;

    private List<StepTokenUsage> steps;
}
