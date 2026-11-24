package com.yu.yuaifather.service.generation;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.mapper.GenerationRunMapper;
import com.yu.yuaifather.model.entity.GenerationRun;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.enums.PipelineType;
import com.yu.yuaifather.model.vo.GenerationTokenUsageVO;
import com.yu.yuaifather.observability.GenerationTokenUsageService;
import com.yu.yuaifather.observability.StepTokenUsage;
import com.yu.yuaifather.observability.TokenUsageReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成运行记录服务。封装 {@link GenerationRun} 的生命周期落库：
 * 开始(RUNNING) → 成功(SUCCESS / FALLBACK_SUCCESS) / 失败(FAILED)。
 *
 * <p><b>容错原则</b>：记录写入失败仅打印日志，绝不阻断生成主流程（对齐"写历史失败不阻断"约定）。</p>
 */
@Slf4j
@Service
public class GenerationRunService {

    /** 增量编辑不是 V2/CLASSIC 枚举值，单独记在 pipeline_type 上。 */
    public static final String PIPELINE_INCREMENTAL = "INCREMENTAL";

    private final GenerationRunMapper generationRunMapper;
    private final MeterRegistry meterRegistry;
    private final GenerationTokenUsageService tokenUsageService;

    public GenerationRunService(GenerationRunMapper generationRunMapper,
                                MeterRegistry meterRegistry,
                                GenerationTokenUsageService tokenUsageService) {
        this.generationRunMapper = generationRunMapper;
        this.meterRegistry = meterRegistry;
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * 记录一次生成开始，返回已落库的记录（含 id）。失败返回内存对象（id 可能为空），不影响主流程。
     */
    public GenerationRun start(String generationId, Long appId, Long userId,
                               String requestedMode, String finalMode,
                               PipelineType preferredPipeline, CodeGenTypeEnum codeGenType) {
        GenerationRun run = GenerationRun.builder()
                .generationId(generationId)
                .appId(appId)
                .userId(userId)
                .requestedMode(requestedMode)
                .finalMode(finalMode)
                .pipelineType(preferredPipeline == null ? null : preferredPipeline.name())
                .status("RUNNING")
                .workspacePath(workspacePath(codeGenType, appId))
                .startedAt(LocalDateTime.now())
                .build();
        try {
            generationRunMapper.insert(run);
        } catch (Exception e) {
            log.warn("generation_run 开始记录写入失败 generationId={}，不影响生成", generationId, e);
        }
        return run;
    }

    /**
     * 增量编辑等非枚举链路的开始记录。pipelineType 直接落库，例如 {@code INCREMENTAL}。
     */
    public GenerationRun start(String generationId, Long appId, Long userId,
                               String requestedMode, String finalMode,
                               String pipelineType, CodeGenTypeEnum codeGenType) {
        GenerationRun run = GenerationRun.builder()
                .generationId(generationId)
                .appId(appId)
                .userId(userId)
                .requestedMode(requestedMode)
                .finalMode(finalMode)
                .pipelineType(pipelineType)
                .status("RUNNING")
                .workspacePath(workspacePath(codeGenType, appId))
                .startedAt(LocalDateTime.now())
                .build();
        try {
            generationRunMapper.insert(run);
        } catch (Exception e) {
            log.warn("generation_run 开始记录写入失败 generationId={}，不影响生成", generationId, e);
        }
        return run;
    }

    /**
     * 记录成功（区分是否发生过降级）。
     *
     * @param successPipeline 实际成功的链路
     * @param fallbackOccurred 是否经历降级
     */
    public void success(GenerationRun run, PipelineType successPipeline, boolean fallbackOccurred) {
        if (run == null) {
            return;
        }
        run.setStatus(fallbackOccurred ? "FALLBACK_SUCCESS" : "SUCCESS");
        run.setPipelineType(successPipeline == null ? run.getPipelineType() : successPipeline.name());
        finish(run);
    }

    /**
     * 记录失败。
     */
    public void fail(GenerationRun run, String errorMessage) {
        if (run == null) {
            return;
        }
        run.setStatus("FAILED");
        run.setErrorMessage(truncate(errorMessage));
        finish(run);
    }

    /**
     * 记录一次降级（来源 → 目标）。
     */
    public void fallback(GenerationRun run, PipelineType from, PipelineType to, String reason) {
        if (run == null) {
            return;
        }
        run.setFallbackFrom(from == null ? null : from.name());
        run.setFallbackTo(to == null ? null : to.name());
        if (reason != null) {
            run.setErrorMessage(truncate(reason));
        }
        update(run);
    }

    /**
     * 把当前内存中的 token 快照写到已有记录上，不改变状态。
     * 用于客户端取消时仍留下已发生的用量。
     */
    public void attachTokens(GenerationRun run) {
        if (run == null) {
            return;
        }
        applyTokenSnapshot(run);
        update(run);
    }

    /**
     * 查询某次生成的分步用量。进行中的生成优先返回内存快照；否则读 generation_run。
     * 查询失败返回 null，不抛给调用方。
     */
    public GenerationTokenUsageVO findTokenUsage(Long appId, String generationId) {
        try {
            if (generationId != null && !generationId.isBlank()) {
                TokenUsageReport live = tokenUsageService.snapshot(generationId);
                if (live != null && appId != null && appId.equals(live.appId())) {
                    return toVo(live, "RUNNING", null);
                }
                GenerationRun run = generationRunMapper.selectOneByQuery(QueryWrapper.create()
                        .eq(GenerationRun::getAppId, appId)
                        .eq(GenerationRun::getGenerationId, generationId)
                        .limit(1));
                return toVo(run);
            }
            TokenUsageReport live = tokenUsageService.liveForApp(appId);
            if (live != null) {
                return toVo(live, "RUNNING", null);
            }
            GenerationRun run = generationRunMapper.selectOneByQuery(QueryWrapper.create()
                    .eq(GenerationRun::getAppId, appId)
                    .orderBy(GenerationRun::getStartedAt, false)
                    .limit(1));
            return toVo(run);
        } catch (Exception e) {
            log.warn("查询 token 用量失败 appId={}: {}", appId, e.getMessage());
            return null;
        }
    }

    private void finish(GenerationRun run) {
        applyTokenSnapshot(run);
        run.setFinishedAt(LocalDateTime.now());
        if (run.getStartedAt() != null) {
            run.setDurationMs(Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        }
        update(run);
        recordMetrics(run);
    }

    private void applyTokenSnapshot(GenerationRun run) {
        try {
            if (run.getGenerationId() == null) {
                return;
            }
            TokenUsageReport report = tokenUsageService.snapshot(run.getGenerationId());
            if (report == null) {
                return;
            }
            run.setInputTokens(report.inputTokens());
            run.setOutputTokens(report.outputTokens());
            run.setTotalTokens(report.totalTokens());
            run.setTokenCallCount(report.callCount());
            run.setUnknownUsageCalls(report.unknownUsageCalls());
            run.setTokenUsageJson(report.toStepsJson());
        } catch (Exception e) {
            log.warn("token 用量写入生成记录失败 generationId={}: {}", run.getGenerationId(), e.getMessage());
        }
    }

    private GenerationTokenUsageVO toVo(TokenUsageReport report, String status, String pipelineType) {
        if (report == null) {
            return null;
        }
        return GenerationTokenUsageVO.builder()
                .generationId(report.generationId())
                .appId(report.appId())
                .status(status)
                .pipelineType(pipelineType)
                .inputTokens(report.inputTokens())
                .outputTokens(report.outputTokens())
                .totalTokens(report.totalTokens())
                .callCount(report.callCount())
                .unknownUsageCalls(report.unknownUsageCalls())
                .steps(report.steps() == null ? List.of() : report.steps())
                .build();
    }

    private GenerationTokenUsageVO toVo(GenerationRun run) {
        if (run == null || !hasTokenPayload(run)) {
            return null;
        }
        return GenerationTokenUsageVO.builder()
                .generationId(run.getGenerationId())
                .appId(run.getAppId())
                .status(run.getStatus())
                .pipelineType(run.getPipelineType())
                .inputTokens(run.getInputTokens() == null ? 0 : run.getInputTokens())
                .outputTokens(run.getOutputTokens() == null ? 0 : run.getOutputTokens())
                .totalTokens(run.getTotalTokens() == null ? 0 : run.getTotalTokens())
                .callCount(run.getTokenCallCount() == null ? 0 : run.getTokenCallCount())
                .unknownUsageCalls(run.getUnknownUsageCalls() == null ? 0 : run.getUnknownUsageCalls())
                .steps(parseSteps(run.getTokenUsageJson(), run.getGenerationId()))
                .build();
    }

    private boolean hasTokenPayload(GenerationRun run) {
        return run.getTokenCallCount() != null
                || run.getTotalTokens() != null
                || (run.getTokenUsageJson() != null && !run.getTokenUsageJson().isBlank());
    }

    private List<StepTokenUsage> parseSteps(String json, String generationId) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JSONObject root = JSONUtil.parseObj(json);
            JSONArray steps = root.getJSONArray("steps");
            if (steps == null || steps.isEmpty()) {
                return List.of();
            }
            List<StepTokenUsage> parsed = new java.util.ArrayList<>(steps.size());
            for (Object item : steps) {
                JSONObject obj = item instanceof JSONObject jsonObject ? jsonObject : JSONUtil.parseObj(item);
                parsed.add(new StepTokenUsage(
                        obj.getStr("step"),
                        obj.getLong("inputTokens", 0L),
                        obj.getLong("outputTokens", 0L),
                        obj.getLong("totalTokens", 0L),
                        obj.getInt("callCount", 0),
                        obj.getInt("unknownUsageCalls", 0),
                        obj.getStr("model")));
            }
            return parsed;
        } catch (Exception e) {
            log.warn("token_usage_json 解析失败 generationId={}: {}", generationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 生成指标打点：generation.duration / generation.total。失败仅 log.warn，不影响主流程。
     */
    private void recordMetrics(GenerationRun run) {
        try {
            String pipeline = tag(run.getPipelineType());
            String mode = tag(run.getFinalMode());
            String status = tag(run.getStatus());
            Counter.builder("generation.total").tags("status", status, "pipeline", pipeline, "mode", mode)
                    .register(meterRegistry).increment();
            if (run.getDurationMs() != null) {
                Timer.builder("generation.duration").tags("status", status, "pipeline", pipeline, "mode", mode)
                        .register(meterRegistry).record(Duration.ofMillis(run.getDurationMs()));
            }
        } catch (Exception e) {
            log.warn("生成指标打点失败 generationId={}: {}", run.getGenerationId(), e.getMessage());
        }
    }

    private String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private void update(GenerationRun run) {
        if (run.getId() == null) {
            // 开始记录未成功落库，补一次插入
            try {
                generationRunMapper.insert(run);
            } catch (Exception e) {
                log.warn("generation_run 补写失败 generationId={}", run.getGenerationId(), e);
            }
            return;
        }
        try {
            generationRunMapper.update(run);
        } catch (Exception e) {
            log.warn("generation_run 更新失败 generationId={}", run.getGenerationId(), e);
        }
    }

    private String workspacePath(CodeGenTypeEnum codeGenType, Long appId) {
        if (codeGenType == null || appId == null) {
            return null;
        }
        return AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType.getValue() + "_" + appId;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
