package com.yu.yuaifather.observability;

import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 generationId 聚合每个步骤的 token，并向已订阅的 SSE 推送快照。
 *
 * <p>记账失败只打日志。没有 generationId 的调用不进入聚合，由监听器标成 {@code unattributed} 写日志。</p>
 */
@Slf4j
@Service
public class GenerationTokenUsageService {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public String newGenerationId() {
        return "gen_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 注册一次生成的用量会话。返回的 Flux 会重放已产生的快照，避免订阅稍晚时丢掉第一笔。
     */
    public Flux<TokenUsageReport> open(String generationId, Long appId) {
        if (generationId == null || generationId.isBlank()) {
            return Flux.empty();
        }
        Session session = new Session(generationId, appId);
        Session existing = sessions.putIfAbsent(generationId, session);
        Session active = existing == null ? session : existing;
        return active.sink.asFlux();
    }

    public void record(String generationId, Long appId, String step, String model, TokenUsage usage) {
        try {
            if (generationId == null || generationId.isBlank()) {
                return;
            }
            Session session = sessions.get(generationId);
            if (session == null) {
                return;
            }
            TokenUsageReport snapshot = session.add(appId, step, model, usage);
            Sinks.EmitResult result = session.sink.tryEmitNext(snapshot);
            if (result.isFailure()) {
                log.warn("token 用量事件投递失败 generationId={} result={}", generationId, result);
            }
        } catch (Exception e) {
            log.warn("token 用量聚合失败 generationId={}: {}", generationId, e.getMessage());
        }
    }

    public TokenUsageReport snapshot(String generationId) {
        if (generationId == null) {
            return null;
        }
        Session session = sessions.get(generationId);
        return session == null ? null : session.snapshot();
    }

    public TokenUsageReport liveForApp(Long appId) {
        if (appId == null) {
            return null;
        }
        TokenUsageReport latest = null;
        long latestAt = Long.MIN_VALUE;
        for (Session session : sessions.values()) {
            if (appId.equals(session.appId) && session.updatedAt > latestAt) {
                latestAt = session.updatedAt;
                latest = session.snapshot();
            }
        }
        return latest;
    }

    public void complete(String generationId) {
        try {
            if (generationId == null) {
                return;
            }
            Session session = sessions.remove(generationId);
            if (session != null) {
                session.sink.tryEmitComplete();
            }
        } catch (Exception e) {
            log.warn("token 用量会话关闭失败 generationId={}: {}", generationId, e.getMessage());
        }
    }

    private static final class Session {
        private final String generationId;
        private final Long appId;
        private final Sinks.Many<TokenUsageReport> sink = Sinks.many().replay().limit(256);
        private final Map<String, MutableStep> steps = new LinkedHashMap<>();
        private String lastStep = LlmSteps.UNATTRIBUTED;
        private long updatedAt = System.nanoTime();

        private Session(String generationId, Long appId) {
            this.generationId = generationId;
            this.appId = appId;
        }

        private synchronized TokenUsageReport add(Long callAppId, String step, String model, TokenUsage usage) {
            if (callAppId != null && this.appId != null && !this.appId.equals(callAppId)) {
                log.warn("token 用量 appId 不一致 generationId={} session={} call={}",
                        generationId, this.appId, callAppId);
            }
            String stepName = LlmCallAttribution.normalizeStep(step);
            MutableStep bucket = steps.computeIfAbsent(stepName, MutableStep::new);
            bucket.add(model, usage);
            lastStep = stepName;
            updatedAt = System.nanoTime();
            return copy();
        }

        private synchronized TokenUsageReport snapshot() {
            return copy();
        }

        private TokenUsageReport copy() {
            List<StepTokenUsage> copied = new ArrayList<>(steps.size());
            long input = 0;
            long output = 0;
            long total = 0;
            int calls = 0;
            int unknown = 0;
            StepTokenUsage current = null;
            for (MutableStep step : steps.values()) {
                StepTokenUsage view = step.view();
                copied.add(view);
                input += view.inputTokens();
                output += view.outputTokens();
                total += view.totalTokens();
                calls += view.callCount();
                unknown += view.unknownUsageCalls();
                if (view.step().equals(lastStep)) {
                    current = view;
                }
            }
            List<StepTokenUsage> frozen = List.copyOf(copied);
            if (current == null) {
                return new TokenUsageReport(generationId, appId, lastStep,
                        0, 0, 0, 0, 0, null,
                        input, output, total, calls, unknown, frozen);
            }
            return new TokenUsageReport(generationId, appId, current.step(),
                    current.inputTokens(), current.outputTokens(), current.totalTokens(),
                    current.callCount(), current.unknownUsageCalls(), current.model(),
                    input, output, total, calls, unknown, frozen);
        }
    }

    private static final class MutableStep {
        private final String step;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private int callCount;
        private int unknownUsageCalls;
        private String model;

        private MutableStep(String step) {
            this.step = step;
        }

        private void add(String modelName, TokenUsage usage) {
            callCount++;
            if (modelName != null && !modelName.isBlank()) {
                model = modelName.length() > 128 ? modelName.substring(0, 128) : modelName;
            }
            if (usage == null
                    || (usage.inputTokenCount() == null
                    && usage.outputTokenCount() == null
                    && usage.totalTokenCount() == null)) {
                unknownUsageCalls++;
                return;
            }
            int input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            int output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            int total = usage.totalTokenCount() == null ? input + output : usage.totalTokenCount();
            inputTokens += Math.max(input, 0);
            outputTokens += Math.max(output, 0);
            totalTokens += Math.max(total, 0);
        }

        private StepTokenUsage view() {
            return new StepTokenUsage(step, inputTokens, outputTokens, totalTokens,
                    callCount, unknownUsageCalls, model);
        }
    }
}
