package com.yu.yuaifather.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yu.yuaifather.mapper.DesignMissLogMapper;
import com.yu.yuaifather.model.entity.DesignMissLog;
import com.yu.yuaifather.service.DesignMissLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 设计检索 miss 日志 服务层实现。
 *
 * @author yu
 */
@Slf4j
@Service
public class DesignMissLogServiceImpl
        extends ServiceImpl<DesignMissLogMapper, DesignMissLog>
        implements DesignMissLogService {

    @Override
    public void recordMiss(DesignMissLog missLog) {
        if (missLog == null) {
            return;
        }
        try {
            if (missLog.getCreateTime() == null) {
                missLog.setCreateTime(LocalDateTime.now());
            }
            this.save(missLog);
        } catch (Exception e) {
            // miss 日志是辅助观测数据，写入失败绝不影响检索主链路
            log.warn("miss 日志写入失败，忽略 profile={} msg={}", missLog.getStyleProfile(), e.getMessage());
        }
    }

    @Override
    public long countMissByProfile(String styleProfile) {
        if (StrUtil.isBlank(styleProfile)) {
            return 0;
        }
        try {
            QueryWrapper qw = QueryWrapper.create()
                    .eq(DesignMissLog::getStyleProfile, styleProfile);
            return this.count(qw);
        } catch (Exception e) {
            log.warn("miss 计数查询失败，按 0 处理 profile={} msg={}", styleProfile, e.getMessage());
            return 0;
        }
    }
}
