package com.yu.yuaifather.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MysqlVectorStore} 向量编解码单元测试（BLOB 小端序 float[] 往返）。
 */
class MysqlVectorStoreCodecTest {

    @Test
    void encodeDecodeShouldRoundTrip() {
        float[] original = new float[]{0.1f, -0.25f, 1.5f, 0f, 3.141592f};
        byte[] bytes = MysqlVectorStore.encodeEmbedding(original);
        assertEquals(original.length * Float.BYTES, bytes.length);
        float[] decoded = MysqlVectorStore.decodeEmbedding(bytes);
        assertArrayEquals(original, decoded, 0f);
    }

    @Test
    void encodeShouldHandleEmptyInput() {
        assertEquals(0, MysqlVectorStore.encodeEmbedding(null).length);
        assertEquals(0, MysqlVectorStore.encodeEmbedding(new float[0]).length);
    }

    @Test
    void decodeShouldHandleInvalidInput() {
        assertEquals(0, MysqlVectorStore.decodeEmbedding(null).length);
        assertEquals(0, MysqlVectorStore.decodeEmbedding(new byte[0]).length);
        // 长度不是 4 的倍数（非法 BLOB）返回空数组
        assertEquals(0, MysqlVectorStore.decodeEmbedding(new byte[]{1, 2, 3}).length);
    }
}
