package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 向量门面单元测试：确认 {@link DesignFingerprintVectorRepository} 只做委托，
 * 不改变原 public 行为（调用方 DesignKnowledgeIndex / VectorSyncWorker 零改动的基础）。
 * <p>
 * 不使用 Mockito（内联 mock maker 需要运行时 attach agent，在受限环境/部分 CI 上不可用），
 * 改为手写记录型 Fake 实现，零额外依赖、结果确定。
 */
class DesignFingerprintVectorRepositoryTest {

    /** 记录调用参数的 Fake 后端。 */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<String> calls = new ArrayList<>();
        private List<DesignFingerprintVector> searchResult = List.of();

        @Override
        public void upsert(Long fingerprintId, String styleProfile, Double confidence, Integer priority,
                           String embeddingText, float[] embedding, String sourceScope, Long ownerUserId,
                           Long projectId, String visibility, String sourceType) {
            calls.add("upsert11:" + fingerprintId + ":" + styleProfile + ":" + sourceScope
                    + ":" + ownerUserId + ":" + visibility + ":" + sourceType
                    + ":" + (embedding == null ? -1 : embedding.length));
        }

        @Override
        public List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                                    int topK, double minSimilarity, Long userId) {
            calls.add("search:" + (queryEmbedding == null ? -1 : queryEmbedding.length)
                    + ":" + styleProfile + ":" + topK + ":" + minSimilarity + ":" + userId);
            return searchResult;
        }

        @Override
        public void deactivate(Long fingerprintId) {
            calls.add("deactivate:" + fingerprintId);
        }
    }

    @Test
    void upsertShouldDelegateToVectorStore() {
        RecordingVectorStore store = new RecordingVectorStore();
        DesignFingerprintVectorRepository repository = new DesignFingerprintVectorRepository(store);
        float[] embedding = new float[]{0.1f, 0.2f};

        repository.upsert(1L, "blog", 0.9, 30, "text", embedding,
                "GLOBAL", null, null, "PUBLIC", "EXTRACTED_FINGERPRINT");
        repository.upsert(2L, "shop", 0.8, 10, "text2", embedding);

        assertEquals(List.of(
                "upsert11:1:blog:GLOBAL:null:PUBLIC:EXTRACTED_FINGERPRINT:2",
                "upsert11:2:shop:GLOBAL:null:PUBLIC:null:2"), store.calls);
    }

    @Test
    void searchShouldDelegateToVectorStore() {
        RecordingVectorStore store = new RecordingVectorStore();
        DesignFingerprintVectorRepository repository = new DesignFingerprintVectorRepository(store);
        float[] query = new float[]{1f, 0f};
        List<DesignFingerprintVector> expected = List.of(DesignFingerprintVector.builder()
                .fingerprintId(1L).similarity(0.9).build());
        store.searchResult = expected;

        List<DesignFingerprintVector> withUser = repository.search(query, null, 3, 0.7, 100L);
        List<DesignFingerprintVector> withoutUser = repository.search(query, "blog", 2, 0.5);

        assertSame(expected, withUser);
        assertSame(expected, withoutUser);
        assertEquals(List.of("search:2:null:3:0.7:100", "search:2:blog:2:0.5:null"), store.calls);
    }

    @Test
    void deactivateShouldDelegateToVectorStore() {
        RecordingVectorStore store = new RecordingVectorStore();
        DesignFingerprintVectorRepository repository = new DesignFingerprintVectorRepository(store);
        repository.deactivate(9L);
        assertEquals(List.of("deactivate:9"), store.calls);
    }
}
