package com.yu.yuaifather.observability;

import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.UserRoleEnum;
import com.yu.yuaifather.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Actuator 端点鉴权过滤器：health 保持开放（供存活探针使用），
 * prometheus / info 等敏感端点要求管理员登录，禁止公网裸露指标数据。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ActuatorAuthFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PATH = "/actuator/";
    private static final String HEALTH_PATH = "/actuator/health";

    /**
     * 惰性获取 UserService：Filter Bean 在 Tomcat 启动阶段（早于普通单例初始化）即被创建，
     * 直接注入会过早触发 Service → Mapper 依赖链，导致 sqlSessionFactory 尚未就绪而启动失败。
     */
    private final ObjectProvider<UserService> userServiceProvider;

    public ActuatorAuthFilter(ObjectProvider<UserService> userServiceProvider) {
        this.userServiceProvider = userServiceProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.contains(ACTUATOR_PATH) || uri.contains(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            User loginUser = userServiceProvider.getObject().getLoginUser(request);
            UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());
            if (UserRoleEnum.ADMIN.equals(userRoleEnum)) {
                filterChain.doFilter(request, response);
                return;
            }
            writeReject(response, HttpServletResponse.SC_FORBIDDEN, "无权访问监控端点");
        } catch (Exception e) {
            // 未登录或会话失效
            writeReject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录");
        }
    }

    private void writeReject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
