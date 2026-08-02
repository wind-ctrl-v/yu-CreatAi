package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.mapper.GenerationRunMapper;
import com.yu.yuaifather.model.entity.GenerationRun;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.enums.PipelineType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 生成运行记录服务。封装 {@link GenerationRun} 的生命周期落库：
 * 开始(RUNNING) → 成功(SUCCESS / FALLBACK_SUCCESS) / 失败(FAILED)。
 *
 * <p><b>容错原则</b>：记录写入失败仅打印日志，绝不阻断生成主流程（对齐"写历史失败不阻断"约定）。</p>
 */
@Slf4j
@Service
public class GenerationRunService {

    private final GenerationRunMapper generationRunMapper;
    private final MeterRegistry meterRegistry;

    public GenerationRunService(GenerationRunMapper generationRunMapper, MeterRegistry meterRegistry) {
        this.generationRunMapper = generationRunMapper;
        this.meterRegistry = meterRegistry;
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

    private void finish(GenerationRun run) {
        run.setFinishedAt(LocalDateTime.now());
        if (run.getStartedAt() != null) {
            run.setDurationMs(Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        }
        update(run);
        recordMetrics(run);
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
