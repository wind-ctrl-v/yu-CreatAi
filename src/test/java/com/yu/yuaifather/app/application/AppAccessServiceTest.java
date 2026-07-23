package com.yu.yuaifather.app.application;

import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.AppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppAccessServiceTest {

    private AppService appService;
    private AppAccessService appAccessService;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        appAccessService = new AppAccessService(appService);
    }

    @Test
    void requireOwnerReturnsOwnedApp() {
        App app = app(1L, 10L);
        when(appService.getById(1L)).thenReturn(app);

        assertSame(app, appAccessService.requireOwner(1L, 10L));
    }

    @Test
    void requireOwnerRejectsAnotherUser() {
        when(appService.getById(1L)).thenReturn(app(1L, 10L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appAccessService.requireOwner(1L, 11L));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    @Test
    void requireOwnerOrAdminAllowsAdmin() {
        App app = app(1L, 10L);
        when(appService.getById(1L)).thenReturn(app);
        User admin = new User();
        admin.setId(99L);
        admin.setUserRole("admin");

        assertSame(app, appAccessService.requireOwnerOrAdmin(1L, admin));
    }

    @Test
    void requireExistingRejectsMissingApp() {
        when(appService.getById(1L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appAccessService.requireExisting(1L));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    private App app(Long id, Long userId) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        return app;
    }
}
