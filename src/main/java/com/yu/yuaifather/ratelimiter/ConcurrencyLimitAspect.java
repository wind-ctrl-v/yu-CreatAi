package com.yu.yuaifather.ratelimiter;

import com.yu.yuaifather.config.LimitsProperties;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * {@link ConcurrencyLimit} 切面：按 key 用公平信号量限制同时执行数。
 * <p>
 * 语义：
 * <ul>
 *   <li>未配置或 {@code max-concurrent <= 0} 时不限制（默认，保持旧行为）；</li>
 *   <li>名额耗尽时立即失败，抛 {@link ErrorCode#TOO_MANY_REQUEST}，避免请求排队把内存拖垮；</li>
 *   <li>返回 {@link Flux} 的 SSE 接口在流终止（{@code doFinally}）后才释放名额；</li>
 *   <li>拒绝时打点 {@code concurrency.rejected{key}}。</li>
 * </ul>
 * 与 {@link RateLimit}（QPS 限流）互补，二者可同时使用。
 */
@Aspect
@Component
@Order(1)
@Slf4j
public class ConcurrencyLimitAspect {

    private final LimitsProperties limitsProperties;
    private final MeterRegistry meterRegistry;

    /** key -> 信号量；配置上限变化时重建。 */
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> permitCache = new ConcurrentHashMap<>();

    public ConcurrencyLimitAspect(LimitsProperties limitsProperties, MeterRegistry meterRegistry) {
        this.limitsProperties = limitsProperties;
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(concurrencyLimit)")
    public Object around(ProceedingJoinPoint point, ConcurrencyLimit concurrencyLimit) throws Throwable {
        String key = concurrencyLimit.value();
        Semaphore semaphore = resolveSemaphore(key);
        if (semaphore == null) {
            // 未启用限制，直接放行
            return point.proceed();
        }
        if (!semaphore.tryAcquire()) {
            recordRejected(key);
            log.warn("并发保护拒绝请求 key={} permits={}", key, semaphore.availablePermits());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "当前请求较多，请稍后重试");
        }
        Object result;
        try {
            result = point.proceed();
        } catch (Throwable t) {
            // 同步异常：立即归还名额
            semaphore.release();
            throw t;
        }
        if (result instanceof Flux<?> flux) {
            // SSE：流真正结束后再释放名额
            return flux.doFinally(signal -> semaphore.release());
        }
        semaphore.release();
        return result;
    }

    /**
     * 解析 key 对应的信号量；未配置/上限 <= 0 返回 null（不限制）。
     */
    private Semaphore resolveSemaphore(String key) {
        int max = maxConcurrent(key);
        if (max <= 0) {
            return null;
        }
        Integer cachedMax = permitCache.get(key);
        if (cachedMax != null && cachedMax == max) {
            Semaphore existing = semaphores.get(key);
            if (existing != null) {
                return existing;
            }
        }
        return semaphores.compute(key, (k, existing) -> {
            Integer prev = permitCache.put(k, max);
            if (existing == null || prev == null || prev != max) {
                return new Semaphore(max, true);
            }
            return existing;
        });
    }

    private int maxConcurrent(String key) {
        LimitsProperties.Limit limit = limitsProperties.getLimits().get(key);
        return limit == null ? 0 : limit.getMaxConcurrent();
    }

    private void recordRejected(String key) {
        try {
            Counter.builder("concurrency.rejected")
                    .tag("key", key)
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.debug("并发拒绝指标打点失败: {}", e.getMessage());
        }
    }
}
