package com.yu.yuaifather.service.deploy;

import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.AppService;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成成功后的本地自动部署：走手动 deployApp，失败不抛回生成链路，且不打开 COS 部署开关。
 */
class LocalAutoDeployTest {

    @Test
    void successfulGenerationUsesTheSameLocalDeployPath() {
        AppService appService = mock(AppService.class);
        User owner = user(7L);
        when(appService.deployApp(42L, owner)).thenReturn("http://localhost:8123/api/static/html_42/");
        LocalAutoDeployService service = new LocalAutoDeployService(appService);

        AutoDeployOutcome outcome = service.tryDeploy(42L, owner);

        assertTrue(outcome.success());
        assertEquals("http://localhost:8123/api/static/html_42/", outcome.deployUrl());
        verify(appService, times(1)).deployApp(42L, owner);
        service.shutdown();
    }

    @Test
    void deployFailureDoesNotEscapeAndKeepsTheUserWarning() {
        AppService appService = mock(AppService.class);
        User stranger = user(8L);
        when(appService.deployApp(42L, stranger))
                .thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用"));
        LocalAutoDeployService service = new LocalAutoDeployService(appService);

        AutoDeployOutcome outcome = service.tryDeploy(42L, stranger);

        assertFalse(outcome.success());
        assertNull(outcome.deployUrl());
        assertEquals("自动部署失败，请手动点击「部署」", AutoDeployOutcome.USER_WARNING);
        service.shutdown();
    }

    @Test
    void buildFailureIsAlsoContained() {
        AppService appService = mock(AppService.class);
        User owner = user(7L);
        when(appService.deployApp(9L, owner)).thenThrow(new IllegalStateException("Vue 项目构建失败"));
        LocalAutoDeployService service = new LocalAutoDeployService(appService);

        AutoDeployOutcome outcome = service.tryDeploy(9L, owner);

        assertFalse(outcome.success());
        verify(appService).deployApp(9L, owner);
        service.shutdown();
    }

    @Test
    void htmlMultiFileAndVueCompletionsAppendLocalDeployBeforeDone() {
        for (String type : List.of("html", "multi_file", "vue_project")) {
            List<String> events = new ArrayList<>();
            AtomicInteger deploys = new AtomicInteger();
            ServerSentEvent<String> chunk = ServerSentEvent.<String>builder().data(type).build();
            Flux<ServerSentEvent<String>> flux = AutoDeploySse.append(
                            Flux.just(chunk),
                            () -> true,
                            () -> {
                                deploys.incrementAndGet();
                                return AutoDeployOutcome.success("http://localhost/api/static/" + type + "_1/");
                            },
                            Runnable::run)
                    .concatWith(MonoDone.done());

            flux.doOnNext(event -> events.add(event.event() == null ? "chunk:" + event.data() : event.event()))
                    .blockLast();

            assertEquals(List.of("chunk:" + type, AutoDeployOutcome.STARTED_EVENT, "done"), events);
            assertEquals(1, deploys.get());
        }
    }

    @Test
    void failedAutoDeployEmitsWarningAndStillCompletes() {
        ServerSentEvent<String> chunk = ServerSentEvent.<String>builder().data("page").build();
        Flux<ServerSentEvent<String>> flux = AutoDeploySse.append(
                        Flux.just(chunk),
                        () -> true,
                        () -> {
                            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
                        },
                        Runnable::run)
                .concatWith(MonoDone.done());

        List<ServerSentEvent<String>> events = flux.collectList().block();
        assertEquals(4, events.size());
        assertEquals("page", events.get(0).data());
        assertEquals(AutoDeployOutcome.STARTED_EVENT, events.get(1).event());
        assertEquals(AutoDeployOutcome.FAILED_EVENT, events.get(2).event());
        assertTrue(events.get(2).data().contains(AutoDeployOutcome.USER_WARNING));
        assertEquals("done", events.get(3).event());
    }

    @Test
    void generationErrorDoesNotDeploy() {
        AtomicInteger deploys = new AtomicInteger();
        Flux<ServerSentEvent<String>> flux = AutoDeploySse.append(
                Flux.error(new IllegalStateException("生成中断")),
                () -> true,
                () -> {
                    deploys.incrementAndGet();
                    return AutoDeployOutcome.success("unused");
                },
                Runnable::run);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> flux.blockLast());
        assertEquals("生成中断", error.getMessage());
        assertEquals(0, deploys.get());
    }

    @Test
    void cancelledSubscriberStillFinishesLocalDeploy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        Executor executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-deploy-test");
            thread.setDaemon(true);
            return thread;
        });

        var subscription = AutoDeploySse.append(
                Flux.empty(),
                () -> true,
                () -> {
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finished.set(true);
                    return AutoDeployOutcome.success("http://localhost/api/static/html_1/");
                },
                executor).subscribe();

        assertTrue(started.await(5, TimeUnit.SECONDS));
        subscription.dispose();
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!finished.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(finished.get(), "客户端断开后本地部署仍应写完 deployKey 与封面任务");
    }

    @Test
    void incrementalEditDeploysOnlyWhenAPageWasWritten() {
        IncrementalPageChange changed = new IncrementalPageChange();
        changed.observe(tool("modifyFile", "文件修改成功: index.html"));
        assertTrue(changed.shouldAutoDeploy());

        IncrementalPageChange vue = new IncrementalPageChange();
        vue.observe(tool("writeFile", "文件写入成功: src/App.vue"));
        assertTrue(vue.shouldAutoDeploy());

        IncrementalPageChange multiFile = new IncrementalPageChange();
        multiFile.observe(tool("readFile", "读取成功"));
        multiFile.observe(tool("modifyFile", "警告：文件中未找到要替换的内容，文件未修改 - style.css"));
        assertFalse(multiFile.shouldAutoDeploy());

        IncrementalPageChange failedAfterWrite = new IncrementalPageChange();
        failedAfterWrite.observe(tool("deleteFile", "文件删除成功: src/unused.vue"));
        failedAfterWrite.observe("{\"type\":\"error\",\"message\":\"编辑失败\"}");
        assertFalse(failedAfterWrite.shouldAutoDeploy());

        IncrementalPageChange missingProject = new IncrementalPageChange();
        missingProject.observe("{\"type\":\"error\",\"message\":\"项目目录不存在，请先生成代码\"}");
        assertFalse(missingProject.shouldAutoDeploy());
    }

    @Test
    void pageWriteObservedDuringTheStreamStillDeploys() {
        IncrementalPageChange pageChange = new IncrementalPageChange();
        String write = tool("modifyFile", "文件修改成功: index.html");
        AtomicInteger deploys = new AtomicInteger();
        Flux<ServerSentEvent<String>> content = Flux.just(write).map(chunk -> {
            pageChange.observe(chunk);
            return ServerSentEvent.<String>builder().data(chunk).build();
        });

        Flux<ServerSentEvent<String>> flux = AutoDeploySse.append(
                        content,
                        pageChange::shouldAutoDeploy,
                        () -> {
                            deploys.incrementAndGet();
                            return AutoDeployOutcome.success("http://localhost/api/static/html_1/");
                        },
                        Runnable::run)
                .concatWith(MonoDone.done());

        List<ServerSentEvent<String>> events = flux.collectList().block();
        assertEquals(3, events.size());
        assertEquals(AutoDeployOutcome.STARTED_EVENT, events.get(1).event());
        assertEquals("done", events.get(2).event());
        assertEquals(1, deploys.get());
    }

    @Test
    void unchangedIncrementalEditSkipsDeploy() {
        AtomicInteger deploys = new AtomicInteger();
        IncrementalPageChange pageChange = new IncrementalPageChange();
        String readOnly = tool("readFile", "文件内容");
        pageChange.observe(readOnly);
        ServerSentEvent<String> chunk = ServerSentEvent.<String>builder().data(readOnly).build();

        Flux<ServerSentEvent<String>> flux = AutoDeploySse.append(
                        Flux.just(chunk),
                        pageChange::shouldAutoDeploy,
                        () -> {
                            deploys.incrementAndGet();
                            return AutoDeployOutcome.success("unused");
                        },
                        Runnable::run)
                .concatWith(MonoDone.done());

        List<ServerSentEvent<String>> events = flux.collectList().block();
        assertEquals(2, events.size());
        assertEquals("done", events.get(1).event());
        assertEquals(0, deploys.get());
    }

    @Test
    void cosOneClickDeployStaysOff() throws Exception {
        try (InputStream in = LocalAutoDeployTest.class.getResourceAsStream("/application.yml")) {
            assertTrue(in != null, "application.yml 应在 classpath 上");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yml.contains("${AGENT_DEPLOY_ENABLED:false}"),
                    "agent.deploy.enabled 必须保持默认关闭，自动封面走本地 deployApp 而不是 COS");
        }
    }

    private static String tool(String name, String result) {
        return "{\"type\":\"tool_executed\",\"name\":\"" + name + "\",\"result\":\"" + result + "\"}";
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    /** 测试里用静态方法拼 done，避免每个用例重复构造。 */
    private static final class MonoDone {
        private static Flux<ServerSentEvent<String>> done() {
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("").build());
        }
    }
}
