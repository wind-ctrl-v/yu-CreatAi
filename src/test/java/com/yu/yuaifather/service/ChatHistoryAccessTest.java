package com.yu.yuaifather.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.ChatHistory;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.impl.ChatHistoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ChatHistoryAccessTest {

    @Test
    void featuredViewerCanReadHistory() {
        App app = app(1L, 10L, AppConstant.GOOD_APP_PRIORITY);
        Page<ChatHistory> page = new Page<>();
        ChatHistoryServiceImpl service = serviceReturning(app, page);

        assertSame(page, service.listAppChatHistoryByPage(1L, 10, null, user(11L, "user")));
    }

    @Test
    void strangerCannotReadPrivateHistory() {
        App app = app(1L, 10L, 0);
        ChatHistoryServiceImpl service = serviceReturning(app, new Page<>());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.listAppChatHistoryByPage(1L, 10, null, user(11L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        assertEquals("无权查看该应用的对话历史", exception.getMessage());
    }

    @Test
    void ownerCanReadPrivateHistory() {
        App app = app(1L, 10L, 0);
        Page<ChatHistory> page = new Page<>();
        ChatHistoryServiceImpl service = serviceReturning(app, page);

        assertSame(page, service.listAppChatHistoryByPage(1L, 10, null, user(10L, "user")));
    }

    private ChatHistoryServiceImpl serviceReturning(App app, Page<ChatHistory> page) {
        ChatHistoryServiceImpl service = spy(new ChatHistoryServiceImpl());
        AppService appService = mock(AppService.class);
        when(appService.getById(app.getId())).thenReturn(app);
        ReflectionTestUtils.setField(service, "appService", appService);
        doReturn(page).when(service).page(any(Page.class), any(QueryWrapper.class));
        return service;
    }

    private App app(Long id, Long userId, Integer priority) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        app.setPriority(priority);
        return app;
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
