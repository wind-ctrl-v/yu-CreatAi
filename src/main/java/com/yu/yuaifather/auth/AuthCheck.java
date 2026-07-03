package com.yu.yuaifather.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解，由 {@link AuthInterceptor} 拦截处理。
 * <p>
 * ★ 包结构说明：本类与 {@code AuthInterceptor} 原分别位于 {@code annotation}/{@code aop} 两个
 * 各只有 1 个文件的微型包，现合并到 {@code auth} 包聚合鉴权相关代码
 * （见包结构优化计划问题 6）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /**
     * 必须有某个角色
     */
    String mustRole() default "";
}
