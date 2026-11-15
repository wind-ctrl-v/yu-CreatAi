package com.yu.yuaifather.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.yu.yuaifather.common.BaseResponse;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserCredentialRedactionTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuvwx.hashed.password";
    private static final AtomicLong IDS = new AtomicLong(1);
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private UserService userService;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    @Test
    void adminQueriesDoNotReturnPasswordHashes() {
        when(userService.getById(7L)).thenAnswer(invocation -> secretUser());
        when(userService.list()).thenAnswer(invocation -> new ArrayList<>(List.of(secretUser())));
        when(userService.page(any())).thenAnswer(invocation -> {
            Page<User> page = new Page<>(1, 10);
            page.setRecords(new ArrayList<>(List.of(secretUser())));
            return page;
        });

        assertNull(controller.getUserById(7L).getData().getUserPassword());
        assertNull(controller.getInfo(7L).getUserPassword());
        assertNull(controller.list().get(0).getUserPassword());
        assertNull(controller.page(new Page<>(1, 10)).getRecords().get(0).getUserPassword());
    }

    @Test
    void jacksonOmitsPasswordAndStillAcceptsItOnInput() throws Exception {
        User user = secretUser();
        String json = objectMapper.writeValueAsString(user);
        assertFalse(json.contains("userPassword"));
        assertFalse(json.contains(HASH));
        assertFalse(json.contains("$2a$"));

        User parsed = objectMapper.readValue(
                "{\"userAccount\":\"admin\",\"userPassword\":\"plain-secret\"}", User.class);
        assertEquals("admin", parsed.getUserAccount());
        assertEquals("plain-secret", parsed.getUserPassword());
    }

    @Test
    void wrappedAdminResponseDoesNotSerializeTheHash() throws Exception {
        when(userService.getById(7L)).thenAnswer(invocation -> secretUser());
        BaseResponse<User> response = controller.getUserById(7L);
        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains("userPassword"));
        assertFalse(json.contains(HASH));
    }

    private static User secretUser() {
        return User.builder()
                .id(IDS.incrementAndGet())
                .userAccount("admin")
                .userPassword(HASH)
                .userRole("admin")
                .build();
    }
}
