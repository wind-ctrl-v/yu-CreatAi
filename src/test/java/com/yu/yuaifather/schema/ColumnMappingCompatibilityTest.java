package com.yu.yuaifather.schema;

import com.mybatisflex.annotation.Column;
import com.yu.yuaifather.mapper.UserPreferenceWeightMapper;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnMappingCompatibilityTest {

    @Test
    void fingerprintUsageCountMapsToCamelCaseColumn() throws Exception {
        Column column = DesignFingerprint.class.getDeclaredField("usageCount").getAnnotation(Column.class);
        assertNotNull(column, "usageCount 必须显式映射，否则 MyBatis-Flex 会查询 usage_count");
        assertEquals("usageCount", column.value());
    }

    @Test
    void preferenceEvidenceUpdateUsesCamelCaseColumns() throws Exception {
        Column evidence = UserPreferenceWeight.class.getDeclaredField("evidenceCount").getAnnotation(Column.class);
        assertEquals("evidenceCount", evidence.value());

        Method method = UserPreferenceWeightMapper.class.getMethod(
                "atomicUpsertWeight", Long.class, String.class, String.class, double.class);
        Update update = method.getAnnotation(Update.class);
        String sql = String.join(" ", update.value());
        assertTrue(sql.contains("`evidenceCount`"));
        assertTrue(sql.contains("`lastEvidenceAt`"));
        assertTrue(sql.contains("`userId`"));
        assertTrue(sql.contains("`preferenceType`"));
        assertTrue(sql.contains("`preferenceKey`"));
        assertFalse(sql.contains("evidence_count"));
        assertFalse(sql.contains("last_evidence_at"));
        assertFalse(sql.contains("user_id"));
        assertFalse(sql.contains("preference_type"));
        assertFalse(sql.contains("preference_key"));

        String worker = Files.readString(Path.of("src/main/java/com/yu/yuaifather/worker/PreferenceUpdateWorker.java"));
        assertFalse(worker.contains(".eq(\"user_id\""));
    }

    @Test
    void compatibilityMigrationsCoverBothColumnNamesWithoutDroppingData() throws Exception {
        String usage = Files.readString(Path.of("docs/sql/022_design_fingerprint_usage_count_compat.sql"));
        assertTrue(usage.contains("usageCount"));
        assertTrue(usage.contains("usage_count"));
        assertTrue(usage.contains("GREATEST"));
        assertTrue(usage.contains("CHANGE COLUMN `usage_count` `usageCount`"));
        assertTrue(usage.contains("ADD COLUMN `usageCount`"));
        assertFalse(usage.toLowerCase().contains("drop column"));
        assertFalse(usage.toLowerCase().contains("drop table"));

        String evidence = Files.readString(Path.of("docs/sql/023_user_preference_evidence_count_compat.sql"));
        for (String token : new String[]{
                "evidenceCount", "evidence_count",
                "lastEvidenceAt", "last_evidence_at",
                "userId", "user_id",
                "preferenceType", "preference_type",
                "preferenceKey", "preference_key",
                "GREATEST"
        }) {
            assertTrue(evidence.contains(token), token);
        }
        assertTrue(evidence.contains("WHERE `userId` IS NULL"));
        assertFalse(evidence.toLowerCase().contains("drop column"));
        assertFalse(evidence.toLowerCase().contains("drop table"));
    }
}
