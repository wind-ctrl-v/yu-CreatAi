package com.yu.yuaifather.observability;

import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.UserRoleEnum;
import com.yu.yuaifather.service.UserService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.yu.yuaifather.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复现并锁定 Actuator 鉴权与 Spring Session 的过滤器顺序：
 * 登录态只存在于 SessionRepositoryFilter 包装后的请求里。
 */
class ActuatorAuthFilterTest {

    @Test
    void declaredOrderIsAfterSpringSession() {
        assertTrue(Ordered.HIGHEST_PRECEDENCE + 20 < SessionRepositoryFilter.DEFAULT_ORDER);
        assertTrue(ActuatorAuthFilter.ORDER > SessionRepositoryFilter.DEFAULT_ORDER);

        Order annotation = ActuatorAuthFilter.class.getAnnotation(Order.class);
        assertNotNull(annotation);
        assertEquals(ActuatorAuthFilter.ORDER, annotation.value());

        ActuatorAuthFilter filter = new ActuatorAuthFilter(fixedProvider(sessionReadingUserService()));
        assertEquals(ActuatorAuthFilter.ORDER, filter.getOrder());
    }

    @Test
    void loggedInAdminCanAccessPrometheusAndInfo() throws Exception {
        User admin = user(UserRoleEnum.ADMIN.getValue());
        for (String uri : List.of("/api/actuator/prometheus", "/api/actuator/info")) {
            Outcome outcome = invoke(uri, admin, ChainOrder.CONTAINER);
            assertEquals(HttpServletResponse.SC_OK, outcome.status(), uri);
            assertEquals("ok", outcome.body(), uri);
            assertTrue(outcome.reachedEndpoint(), uri);
        }
    }

    @Test
    void anonymousPrometheusAndInfoStayUnauthorized() throws Exception {
        for (String uri : List.of("/api/actuator/prometheus", "/api/actuator/info")) {
            Outcome outcome = invoke(uri, null, ChainOrder.CONTAINER);
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, outcome.status(), uri);
            assertEquals("{\"code\":401,\"message\":\"未登录\"}", outcome.body(), uri);
            assertFalse(outcome.reachedEndpoint(), uri);
        }
    }

    @Test
    void loggedInNonAdminIsForbidden() throws Exception {
        Outcome outcome = invoke("/api/actuator/prometheus", user(UserRoleEnum.USER.getValue()), ChainOrder.CONTAINER);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, outcome.status());
        assertEquals("{\"code\":403,\"message\":\"无权访问监控端点\"}", outcome.body());
        assertFalse(outcome.reachedEndpoint());
    }

    @Test
    void healthEndpointsStayPublicWithoutLogin() throws Exception {
        for (String uri : List.of("/api/actuator/health", "/api/actuator/health/liveness", "/api/health/")) {
            Outcome outcome = invoke(uri, null, ChainOrder.CONTAINER);
            assertEquals(HttpServletResponse.SC_OK, outcome.status(), uri);
            assertEquals("ok", outcome.body(), uri);
            assertTrue(outcome.reachedEndpoint(), uri);
        }
    }

    @Test
    void adminSessionIsInvisibleWhenAuthFilterRunsBeforeSpringSession() throws Exception {
        Outcome outcome = invoke("/api/actuator/prometheus", user(UserRoleEnum.ADMIN.getValue()), ChainOrder.AUTH_BEFORE_SESSION);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, outcome.status());
        assertEquals("{\"code\":401,\"message\":\"未登录\"}", outcome.body());
        assertFalse(outcome.reachedEndpoint());
    }

    private Outcome invoke(String uri, User sessionUser, ChainOrder chainOrder) throws Exception {
        MapSessionRepository repository = new MapSessionRepository(new ConcurrentHashMap<>());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (sessionUser != null) {
            MapSession session = repository.createSession();
            session.setAttribute(USER_LOGIN_STATE, sessionUser);
            repository.save(session);
            request.setCookies(new Cookie("SESSION", session.getId()));
        }

        DefaultCookieSerializer cookieSerializer = new DefaultCookieSerializer();
        cookieSerializer.setUseBase64Encoding(false);
        CookieHttpSessionIdResolver idResolver = new CookieHttpSessionIdResolver();
        idResolver.setCookieSerializer(cookieSerializer);

        SessionRepositoryFilter<MapSession> sessionFilter = new SessionRepositoryFilter<>(repository);
        sessionFilter.setHttpSessionIdResolver(idResolver);
        ActuatorAuthFilter authFilter = new ActuatorAuthFilter(fixedProvider(sessionReadingUserService()));

        List<Filter> filters = new ArrayList<>();
        if (chainOrder == ChainOrder.CONTAINER) {
            filters.add(authFilter);
            filters.add(sessionFilter);
            filters.sort((left, right) -> Integer.compare(registrationOrder(left), registrationOrder(right)));
            assertInstanceOf(SessionRepositoryFilter.class, filters.get(0));
            assertInstanceOf(ActuatorAuthFilter.class, filters.get(1));
        } else {
            filters.add(authFilter);
            filters.add(sessionFilter);
        }

        AtomicBoolean reached = new AtomicBoolean(false);
        HttpServlet endpoint = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
                reached.set(true);
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write("ok");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(endpoint, filters.toArray(Filter[]::new)).doFilter(request, response);
        return new Outcome(response.getStatus(), response.getContentAsString(), reached.get());
    }

    /**
     * 与 Spring Boot 注册 SessionRepositoryFilter 的方式一致：顺序取默认值，
     * 而不是过滤器实例自己是否实现 {@link Ordered}。
     */
    private static int registrationOrder(Filter filter) {
        if (filter instanceof ActuatorAuthFilter authFilter) {
            return authFilter.getOrder();
        }
        if (filter instanceof SessionRepositoryFilter<?>) {
            return SessionRepositoryFilter.DEFAULT_ORDER;
        }
        throw new IllegalArgumentException(filter.getClass().getName());
    }

    /**
     * 只复刻 {@code UserServiceImpl#getLoginUser} 的会话读取。数据库回源不在本过滤器的缺陷里。
     */
    private static UserService sessionReadingUserService() {
        UserService userService = mock(UserService.class);
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenAnswer(invocation -> {
            HttpServletRequest current = invocation.getArgument(0);
            Object userObj = current.getSession().getAttribute(USER_LOGIN_STATE);
            if (!(userObj instanceof User user) || user.getId() == null) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
            }
            return user;
        });
        return userService;
    }

    private static User user(String role) {
        return User.builder().id(7L).userAccount("admin").userRole(role).build();
    }

    private static ObjectProvider<UserService> fixedProvider(UserService userService) {
        return new ObjectProvider<>() {
            @Override
            public UserService getObject() {
                return userService;
            }

            @Override
            public UserService getObject(Object... args) {
                return userService;
            }

            @Override
            public UserService getIfAvailable() {
                return userService;
            }

            @Override
            public UserService getIfUnique() {
                return userService;
            }
        };
    }

    private enum ChainOrder {
        CONTAINER,
        AUTH_BEFORE_SESSION
    }

    private record Outcome(int status, String body, boolean reachedEndpoint) {
    }
}
