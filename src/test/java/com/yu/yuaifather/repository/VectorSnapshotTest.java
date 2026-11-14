package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorSnapshot} 过滤与 TopK 单元测试（覆盖 MySQL 后端与原 PG SQL 等价的过滤口径）。
 */
class VectorSnapshotTest {

    private static DesignFingerprintVector vector(long fingerprintId, float[] embedding,
                                                  String styleProfile, String sourceScope,
                                                  Long ownerUserId, String status, boolean active) {
        return DesignFingerprintVector.builder()
                .fingerprintId(fingerprintId)
                .embedding(embedding)
                .styleProfile(styleProfile)
                .sourceScope(sourceScope)
                .ownerUserId(ownerUserId)
                .status(status)
                .isActive(active)
                .priority(10)
                .confidence(0.9)
                .build();
    }

    private static VectorSnapshot snapshot() {
        return new VectorSnapshot(List.of(
                vector(1L, new float[]{1f, 0f}, "blog", "GLOBAL", null, "APPROVED", true),
                vector(2L, new float[]{0f, 1f}, "blog", "GLOBAL", null, "APPROVED", true),
                vector(3L, new float[]{1f, 0f}, "shop", "GLOBAL", null, "APPROVED", true),
                vector(4L, new float[]{1f, 0f}, "blog", "USER", 100L, "APPROVED", true),
                vector(5L, new float[]{1f, 0f}, "blog", "GLOBAL", null, "REJECTED", true),
                vector(6L, new float[]{1f, 0f}, "blog", "GLOBAL", null, "APPROVED", false)
        ));
    }

    @Test
    void searchShouldFilterByActiveAndApprovedStatus() {
        VectorSnapshot snapshot = snapshot();
        List<DesignFingerprintVector> result = snapshot.search(new float[]{1f, 0f}, null, 10, 0d, null);
        // 排除 REJECTED(5) 与 inactive(6)；1/2/3 为 GLOBAL，4 为用户100私有（userId=null 时不加权限过滤）
        assertEquals(4, result.size());
        assertTrue(result.stream().noneMatch(v -> v.getFingerprintId() == 5L));
        assertTrue(result.stream().noneMatch(v -> v.getFingerprintId() == 6L));
    }

    @Test
    void searchShouldFilterByStyleProfileWhenProvided() {
        VectorSnapshot snapshot = snapshot();
        List<DesignFingerprintVector> result = snapshot.search(new float[]{1f, 0f}, "shop", 10, 0d, null);
        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getFingerprintId());
    }

    @Test
    void searchShouldApplyPermissionFilterWhenUserIdProvided() {
        VectorSnapshot snapshot = snapshot();
        // 用户 100：GLOBAL(1/2/3) + 本人私有(4) = 4
        List<DesignFingerprintVector> own = snapshot.search(new float[]{1f, 0f}, null, 10, 0d, 100L);
        assertEquals(4, own.size());

        // 用户 200：只有 GLOBAL(1/2/3)，用户 100 的私有指纹(4)不可见
        List<DesignFingerprintVector> other = snapshot.search(new float[]{1f, 0f}, null, 10, 0d, 200L);
        assertEquals(3, other.size());
        assertTrue(other.stream().noneMatch(v -> v.getFingerprintId() == 4L));
    }

    @Test
    void putAndRemoveShouldUpdateSnapshot() {
        VectorSnapshot snapshot = VectorSnapshot.empty();
        assertTrue(snapshot.isEmpty());
        snapshot.put(vector(1L, new float[]{1f, 0f}, "blog", "GLOBAL", null, "APPROVED", true));
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.contains(1L));
        snapshot.remove(1L);
        assertFalse(snapshot.contains(1L));
        assertEquals(0, snapshot.size());
        // 无效记录忽略
        snapshot.put(null);
        snapshot.put(vector(2L, new float[0], "blog", "GLOBAL", null, "APPROVED", true));
        assertEquals(0, snapshot.size());
    }

    @Test
    void replaceAllShouldReplaceWholeSnapshot() {
        VectorSnapshot snapshot = snapshot();
        snapshot.replaceAll(List.of(vector(9L, new float[]{1f, 0f}, "blog", "GLOBAL", null, "APPROVED", true)));
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.contains(9L));
        assertFalse(snapshot.contains(1L));
    }
}
