package com.yu.yuaifather.mapper;

import com.mybatisflex.core.BaseMapper;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户偏好权重 映射层。
 *
 * @author yu
 */
public interface UserPreferenceWeightMapper extends BaseMapper<UserPreferenceWeight> {

    /**
     * 原子更新偏好权重：weight = weight + delta。
     * <p>
     * 利用 MySQL UPDATE 的行锁保证并发安全，避免 read-then-write 竞态。
     *
     * @return 受影响的行数（0 = 记录不存在，需要 INSERT）
     */
    @Update("UPDATE user_preference_weight SET weight = weight + #{delta}, " +
            "evidence_count = evidence_count + 1, last_evidence_at = NOW() " +
            "WHERE user_id = #{userId} AND preference_type = #{type} AND preference_key = #{key}")
    int atomicUpsertWeight(@Param("userId") Long userId,
                            @Param("type") String type,
                            @Param("key") String key,
                            @Param("delta") double delta);
}
