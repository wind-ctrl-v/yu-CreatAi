package com.yu.yuaifather.ratelimiter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 并发保护注解：限制同一 key 下同时执行的方法数（单机信号量实现）。
 * <p>
 * 与 {@link RateLimit}（Redisson 分布式 QPS 限流）互补：本注解控制"同时执行数"，
 * 用于 2C4G 单机部署时保护内存（多个并发生成/构建会把 4G 内存打满）。
 * <p>
 * 上限取自配置 {@code app.limits.<key>.max-concurrent}，默认 0 = 不限制（保持旧行为）。
 * 支持 SSE 方法：返回 {@code Flux} 时在流终止（doFinally）后释放名额。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConcurrencyLimit {

    /** 限制 key，对应 {@code app.limits.<key>.max-concurrent}。 */
    String value();
}
