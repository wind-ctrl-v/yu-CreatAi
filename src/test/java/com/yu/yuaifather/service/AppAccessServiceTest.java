package com.yu.yuaifather.service;

import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void featuredSampleIsViewableByAnotherLoggedInUser() {
        App app = featured(1L, 10L);
        when(appService.getById(1L)).thenReturn(app);

        assertSame(app, appAccessService.requireView(1L, user(11L, "user")));
        assertTrue(AppAccessService.canView(app, user(11L, "user")));
    }

    @Test
    void featuredSampleStaysImmutableForAnotherUser() {
        when(appService.getById(1L)).thenReturn(featured(1L, 10L));

        BusinessException ownerOnly = assertThrows(BusinessException.class,
                () -> appAccessService.requireOwner(1L, 11L));
        BusinessException ownerOrAdmin = assertThrows(BusinessException.class,
                () -> appAccessService.requireOwnerOrAdmin(1L, user(11L, "user")));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ownerOnly.getCode());
        assertEquals("无权访问该应用", ownerOnly.getMessage());
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), ownerOrAdmin.getCode());
    }

    @Test
    void privateAppIsNotViewableByAnotherUser() {
        App app = app(1L, 10L);
        app.setPriority(0);
        when(appService.getById(1L)).thenReturn(app);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appAccessService.requireView(1L, user(11L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        assertEquals("无权访问该应用", exception.getMessage());
        assertFalse(AppAccessService.canView(app, user(11L, "user")));
    }

    @Test
    void ownerAndAdminCanViewPrivateApp() {
        App app = app(1L, 10L);
        when(appService.getById(1L)).thenReturn(app);

        assertSame(app, appAccessService.requireView(1L, user(10L, "user")));
        assertSame(app, appAccessService.requireView(1L, user(99L, "admin")));
    }

    @Test
    void viewRequiresLoginAndExactFeaturedPriority() {
        App almost = app(1L, 10L);
        almost.setPriority(98);
        assertFalse(AppAccessService.canView(almost, user(11L, "user")));
        assertFalse(AppAccessService.canView(featured(1L, 10L), null));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> appAccessService.requireView(1L, null));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), exception.getCode());
    }

    private App app(Long id, Long userId) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        return app;
    }

    private App featured(Long id, Long userId) {
        App app = app(id, userId);
        app.setPriority(AppConstant.GOOD_APP_PRIORITY);
        return app;
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
