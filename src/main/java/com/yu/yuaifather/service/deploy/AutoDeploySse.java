package com.yu.yuaifather.service.deploy;

import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 在生成流成功结束后追加本地自动部署事件。
 * <p>
 * 上游失败时不会订阅部署步骤。部署异常被收成 {@code auto-deploy-failed}，
 * 流本身仍正常结束，调用方可以继续发送 {@code done}。
 */
public final class AutoDeploySse {

    private static final Logger log = LoggerFactory.getLogger(AutoDeploySse.class);

    private AutoDeploySse() {
    }

    public static Flux<ServerSentEvent<String>> append(Flux<ServerSentEvent<String>> content,
                                                       BooleanSupplier shouldDeploy,
                                                       Supplier<AutoDeployOutcome> deploy,
                                                       Executor executor) {
        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            if (shouldDeploy != null && !shouldDeploy.getAsBoolean()) {
                return Flux.empty();
            }
            return Flux.concat(Flux.just(startedEvent()), deployEvents(deploy, executor));
        });
        return content.concatWith(tail);
    }

    private static Flux<ServerSentEvent<String>> deployEvents(Supplier<AutoDeployOutcome> deploy,
                                                              Executor executor) {
        return runDeploy(deploy, executor).flatMapMany(outcome -> {
            if (outcome.success()) {
                return Flux.empty();
            }
            return Flux.just(failedEvent());
        });
    }

    /**
     * 部署提交到独立线程。订阅被取消时不中断任务，生成已经成功，deployKey 与封面仍应写完。
     */
    private static Mono<AutoDeployOutcome> runDeploy(Supplier<AutoDeployOutcome> deploy, Executor executor) {
        return Mono.create(sink -> {
            Runnable task = () -> {
                AutoDeployOutcome outcome;
                try {
                    outcome = deploy.get();
                } catch (Exception e) {
                    log.warn("自动本地部署任务异常，生成结果已保留: {}", e.getMessage());
                    outcome = AutoDeployOutcome.failed();
                }
                try {
                    sink.success(outcome);
                } catch (Exception ignored) {
                    // 客户端已断开时 sink 可能拒绝完成信号，部署副作用已经发生
                }
            };
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                log.warn("自动本地部署线程池已满，生成结果已保留，可手动部署");
                sink.success(AutoDeployOutcome.failed());
            }
        });
    }

    private static ServerSentEvent<String> startedEvent() {
        return ServerSentEvent.<String>builder()
                .event(AutoDeployOutcome.STARTED_EVENT)
                .data("{}")
                .build();
    }

    private static ServerSentEvent<String> failedEvent() {
        String data = JSONUtil.toJsonStr(Map.of("message", AutoDeployOutcome.USER_WARNING));
        return ServerSentEvent.<String>builder()
                .event(AutoDeployOutcome.FAILED_EVENT)
                .data(data)
                .build();
    }
}
