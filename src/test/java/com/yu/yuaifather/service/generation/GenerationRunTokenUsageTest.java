package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.mapper.GenerationRunMapper;
import com.yu.yuaifather.model.entity.GenerationRun;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.enums.PipelineType;
import com.yu.yuaifather.model.vo.GenerationTokenUsageVO;
import com.yu.yuaifather.observability.GenerationTokenUsageService;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationRunTokenUsageTest {

    @Test
    void successPersistsPerStepTokensAndQueryReturnsThem() {
        GenerationRunMapper mapper = mock(GenerationRunMapper.class);
        when(mapper.insert(any(GenerationRun.class))).thenAnswer(invocation -> {
            GenerationRun run = invocation.getArgument(0);
            run.setId(11L);
            return 1;
        });
        when(mapper.update(any(GenerationRun.class))).thenReturn(1);

        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        GenerationRunService runs = new GenerationRunService(mapper, new SimpleMeterRegistry(), usage);
        String generationId = usage.newGenerationId();
        usage.open(generationId, 77L);
        usage.record(generationId, 77L, "PlanningAgent", "deepseek-chat", new TokenUsage(20, 10, 30));
        usage.record(generationId, 77L, "CriticAgent", "deepseek-chat", new TokenUsage(4, 6, 10));
        usage.record(generationId, 77L, "CriticAgent", "deepseek-chat", null);

        GenerationRun started = runs.start(generationId, 77L, 3L, "high_quality", "high_quality",
                PipelineType.V2, CodeGenTypeEnum.VUE_PROJECT);
        runs.success(started, PipelineType.V2, false);

        assertEquals(40L, started.getTotalTokens());
        assertEquals(24L, started.getInputTokens());
        assertEquals(16L, started.getOutputTokens());
        assertEquals(3, started.getTokenCallCount());
        assertEquals(1, started.getUnknownUsageCalls());
        assertTrue(started.getTokenUsageJson().contains("PlanningAgent"));
        assertTrue(started.getTokenUsageJson().contains("CriticAgent"));
        verify(mapper).update(started);

        when(mapper.selectOneByQuery(any())).thenReturn(started);
        usage.complete(generationId);
        GenerationTokenUsageVO vo = runs.findTokenUsage(77L, generationId);
        assertNotNull(vo);
        assertEquals(generationId, vo.getGenerationId());
        assertEquals("SUCCESS", vo.getStatus());
        assertEquals("V2", vo.getPipelineType());
        assertEquals(2, vo.getSteps().size());
        assertEquals("PlanningAgent", vo.getSteps().get(0).step());
        assertEquals(30, vo.getSteps().get(0).totalTokens());
        assertEquals(2, vo.getSteps().get(1).callCount());
        assertEquals(1, vo.getSteps().get(1).unknownUsageCalls());
        assertEquals(40, vo.getTotalTokens());
    }

    @Test
    void liveSnapshotIsReturnedWhileTheRunIsStillOpen() {
        GenerationRunMapper mapper = mock(GenerationRunMapper.class);
        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        GenerationRunService runs = new GenerationRunService(mapper, new SimpleMeterRegistry(), usage);
        String generationId = usage.newGenerationId();
        usage.open(generationId, 8L);
        usage.record(generationId, 8L, "ClassicHtml", "deepseek-chat", new TokenUsage(9, 1, 10));

        GenerationTokenUsageVO vo = runs.findTokenUsage(8L, null);
        assertEquals(generationId, vo.getGenerationId());
        assertEquals("RUNNING", vo.getStatus());
        assertEquals("ClassicHtml", vo.getSteps().get(0).step());
        assertEquals(10, vo.getTotalTokens());
    }

    @Test
    void runWithoutTokenColumnsIsNotPresentedAsZero() {
        GenerationRunMapper mapper = mock(GenerationRunMapper.class);
        GenerationRun historical = GenerationRun.builder()
                .generationId("gen_old")
                .appId(1L)
                .status("SUCCESS")
                .build();
        when(mapper.selectOneByQuery(any())).thenReturn(historical);
        GenerationRunService runs = new GenerationRunService(
                mapper, new SimpleMeterRegistry(), new GenerationTokenUsageService());
        assertNull(runs.findTokenUsage(1L, "gen_old"));
    }

    @Test
    void migrationAddsTokenColumnsWithoutDroppingExistingData() throws Exception {
        String sql = Files.readString(Path.of("docs/sql/migrations/024_generation_run_token_usage.sql"));
        for (String column : new String[]{
                "input_tokens", "output_tokens", "total_tokens",
                "token_call_count", "unknown_usage_calls", "token_usage_json"
        }) {
            assertTrue(sql.contains(column), column);
        }
        assertTrue(sql.contains("ALTER TABLE generation_run"));
        assertFalse(sql.toLowerCase().contains("drop column"));
        assertFalse(sql.toLowerCase().contains("drop table"));

        assertEquals("input_tokens", column("inputTokens"));
        assertEquals("output_tokens", column("outputTokens"));
        assertEquals("total_tokens", column("totalTokens"));
        assertEquals("token_call_count", column("tokenCallCount"));
        assertEquals("unknown_usage_calls", column("unknownUsageCalls"));
        assertEquals("token_usage_json", column("tokenUsageJson"));
    }

    private static String column(String field) throws Exception {
        return GenerationRun.class.getDeclaredField(field)
                .getAnnotation(com.mybatisflex.annotation.Column.class)
                .value();
    }
}
