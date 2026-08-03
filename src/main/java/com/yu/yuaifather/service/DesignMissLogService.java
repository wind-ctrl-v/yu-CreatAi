package com.yu.yuaifather.service;

import com.mybatisflex.core.service.IService;
import com.yu.yuaifather.model.entity.DesignMissLog;

/**
 * 设计检索 miss 日志 服务层（覆盖度优化计划 阶段一/二）。
 *
 * @author yu
 */
public interface DesignMissLogService extends IService<DesignMissLog> {

    /**
     * 记录一次检索 miss（失败仅记日志，不影响检索主链路）。
     *
     * @param missLog miss 现场
     */
    void recordMiss(DesignMissLog missLog);

    /**
     * 统计指定风格画像的 miss 次数（动态合成触发判定用，阶段二）。
     *
     * @param styleProfile 归一化后的风格画像
     * @return miss 次数
     */
    long countMissByProfile(String styleProfile);
}
