package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorMath} 纯函数单元测试：余弦相似度、TopK 排序与阈值过滤。
 */
class VectorMathTest {

    private static DesignFingerprintVector vector(long fingerprintId, float[] embedding, int priority, double confidence) {
        return DesignFingerprintVector.builder()
                .fingerprintId(fingerprintId)
                .embedding(embedding)
                .priority(priority)
                .confidence(confidence)
                .status("APPROVED")
                .isActive(true)
                .styleProfile("blog")
                .build();
    }

    @Test
    void normShouldComputeL2Norm() {
        assertEquals(5d, VectorMath.norm(new float[]{3f, 4f}), 1e-9);
        assertEquals(0d, VectorMath.norm(new float[0]), 1e-9);
        assertEquals(0d, VectorMath.norm(null), 1e-9);
    }

    @Test
    void cosineShouldHandleNormalAndInvalidInputs() {
        assertEquals(1d, VectorMath.cosine(new float[]{1f, 2f}, new float[]{1f, 2f}), 1e-9);
        assertEquals(0d, VectorMath.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}), 1e-9);
        assertEquals(-1d, VectorMath.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f}), 1e-9);
        // 零向量 / 维度不一致 / null 统一返回 0
        assertEquals(0d, VectorMath.cosine(new float[]{0f, 0f}, new float[]{1f, 1f}), 1e-9);
        assertEquals(0d, VectorMath.cosine(new float[]{1f}, new float[]{1f, 1f}), 1e-9);
        assertEquals(0d, VectorMath.cosine(null, new float[]{1f}), 1e-9);
    }

    @Test
    void topKShouldReturnSortedCopiesAndRespectThreshold() {
        List<DesignFingerprintVector> candidates = new ArrayList<>();
        DesignFingerprintVector identical = vector(1L, new float[]{1f, 0f}, 10, 0.9);
        DesignFingerprintVector near = vector(2L, new float[]{0.9f, 0.1f}, 30, 0.8);
        DesignFingerprintVector orthogonal = vector(3L, new float[]{0f, 1f}, 99, 0.99);
        candidates.add(near);
        candidates.add(identical);
        candidates.add(orthogonal);

        List<DesignFingerprintVector> result = VectorMath.topK(new float[]{1f, 0f}, candidates, 2, 0.5);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getFingerprintId());
        assertEquals(1d, result.get(0).getSimilarity(), 1e-9);
        assertEquals(2L, result.get(1).getFingerprintId());
        // 返回的是副本：原对象 similarity 仍为 null
        assertEquals(null, identical.getSimilarity());
    }

    @Test
    void topKShouldSkipDimensionMismatchAndReturnEmptyOnInvalidInput() {
        List<DesignFingerprintVector> candidates = new ArrayList<>();
        candidates.add(vector(1L, new float[]{1f}, 10, 0.9));
        assertEquals(0, VectorMath.topK(new float[]{1f, 0f}, candidates, 3, 0d).size());
        assertEquals(0, VectorMath.topK(new float[0], candidates, 3, 0d).size());
        assertEquals(0, VectorMath.topK(new float[]{1f}, candidates, 0, 0d).size());
        assertTrue(VectorMath.topK(new float[]{1f}, null, 3, 0d).isEmpty());
    }

    @Test
    void topKShouldPreferHigherPriorityWhenSimilarityTies() {
        List<DesignFingerprintVector> candidates = new ArrayList<>();
        candidates.add(vector(1L, new float[]{1f, 0f}, 10, 0.5));
        candidates.add(vector(2L, new float[]{1f, 0f}, 50, 0.9));
        List<DesignFingerprintVector> result = VectorMath.topK(new float[]{1f, 0f}, candidates, 2, 0d);
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getFingerprintId());
    }
}
