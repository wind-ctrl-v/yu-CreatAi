package com.yu.yuaifather.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.yuaifather.mapper.UserDesignEventMapper;
import com.yu.yuaifather.mapper.UserDesignProfileMapper;
import com.yu.yuaifather.mapper.UserPreferenceWeightMapper;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferenceWeightUpsertTest {

    private UserPreferenceWeightMapper weightMapper;
    private PreferenceUpdateWorker worker;

    @BeforeEach
    void setUp() {
        weightMapper = mock(UserPreferenceWeightMapper.class);
        worker = new PreferenceUpdateWorker(
                mock(UserDesignEventMapper.class),
                weightMapper,
                mock(UserDesignProfileMapper.class),
                new ObjectMapper(),
                Runnable::run);
    }

    @Test
    void firstWriteInsertsCamelCaseRowWithTimestamps() {
        when(weightMapper.atomicUpsertWeight(7L, "color", "蓝色", 0.15)).thenReturn(0);
        when(weightMapper.insertSelective(any(UserPreferenceWeight.class))).thenReturn(1);

        worker.updateSingleWeight(7L, "color", "蓝色", 0.15);

        ArgumentCaptor<UserPreferenceWeight> saved = ArgumentCaptor.forClass(UserPreferenceWeight.class);
        verify(weightMapper).insertSelective(saved.capture());
        verify(weightMapper, never()).insert(any());
        UserPreferenceWeight row = saved.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals("color", row.getPreferenceType());
        assertEquals("蓝色", row.getPreferenceKey());
        assertEquals(0.15, row.getWeight());
        assertEquals(1, row.getEvidenceCount());
        assertNotNull(row.getLastEvidenceAt());
        assertNotNull(row.getCreateTime());
        assertNotNull(row.getUpdateTime());
    }

    @Test
    void nullCreateTimeFailureIsNotTreatedAsSuccess() {
        when(weightMapper.atomicUpsertWeight(7L, "style", "清爽", 0.15)).thenReturn(0);
        when(weightMapper.insertSelective(any(UserPreferenceWeight.class)))
                .thenThrow(new PersistenceException("insert",
                        new SQLException("Column 'createTime' cannot be null", "23000", 1048)));

        PersistenceException error = assertThrows(PersistenceException.class,
                () -> worker.updateSingleWeight(7L, "style", "清爽", 0.15));
        assertEquals(1048, ((SQLException) error.getCause()).getErrorCode());
        verify(weightMapper, times(1)).atomicUpsertWeight(7L, "style", "清爽", 0.15);
    }

    @Test
    void duplicateKeyRetriesUpdateAndFailsWhenRetryMisses() {
        when(weightMapper.atomicUpsertWeight(7L, "style", "极简", 0.15)).thenReturn(0, 0);
        when(weightMapper.insertSelective(any(UserPreferenceWeight.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> worker.updateSingleWeight(7L, "style", "极简", 0.15));
        assertEquals("偏好权重并发插入后更新仍未命中 userId=7 type=style key=极简", error.getMessage());
        verify(weightMapper, times(2)).atomicUpsertWeight(7L, "style", "极简", 0.15);
    }

    @Test
    void duplicateKeyRetryThatUpdatesIsSuccessful() {
        when(weightMapper.atomicUpsertWeight(7L, "style", "极简", 0.15)).thenReturn(0, 1);
        when(weightMapper.insertSelective(any(UserPreferenceWeight.class)))
                .thenThrow(new PersistenceException("dup", new SQLException("Duplicate entry", "23000", 1062)));

        worker.updateSingleWeight(7L, "style", "极简", 0.15);

        verify(weightMapper, times(2)).atomicUpsertWeight(7L, "style", "极简", 0.15);
    }
}
