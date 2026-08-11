package com.yu.yuaifather.agent.core;

import com.yu.yuaifather.model.dto.agent.PromptContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class BlackboardPromptContextTest {

    @Test
    void storesTypedPromptContextAndIgnoresNullOverwrite() {
        Blackboard blackboard = new Blackboard();
        PromptContext context = PromptContext.builder()
                .styleProfile("editorial")
                .fullPrompt("用户偏好与品牌设计上下文")
                .build();

        blackboard.putPromptContext(context);
        blackboard.putPromptContext(null);

        assertSame(context, blackboard.getPromptContext());
    }
}
