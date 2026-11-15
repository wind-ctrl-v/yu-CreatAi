package com.yu.yuaifather.codegen.routing;

import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiCodeGenTypeRoutingServiceImplTest {

    @Test
    void parsesJsonObjectField() {
        assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("{\"codeGenType\":\"VUE_PROJECT\"}"));
        assertEquals(CodeGenTypeEnum.MULTI_FILE,
                AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("{\"type\":\"multi_file\"}"));
        assertEquals(CodeGenTypeEnum.HTML,
                AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("{\"value\":\"html\"}"));
    }

    @Test
    void parsesFencedAndWrappedJson() {
        String raw = """
                ```json
                {"codeGenType":"VUE_PROJECT"}
                ```
                """;
        assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                AiCodeGenTypeRoutingServiceImpl.parseCodeGenType(raw));
    }

    @Test
    void parsesBareEnumNameAndValue() {
        assertEquals(CodeGenTypeEnum.HTML, AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("HTML"));
        assertEquals(CodeGenTypeEnum.MULTI_FILE, AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("MULTI_FILE"));
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("vue_project"));
    }

    @Test
    void rejectsTruncatedJson() {
        assertNull(AiCodeGenTypeRoutingServiceImpl.parseCodeGenType("{"));
        assertNull(AiCodeGenTypeRoutingServiceImpl.parseCodeGenType(""));
        assertNull(AiCodeGenTypeRoutingServiceImpl.parseCodeGenType(null));
    }

    @Test
    void prefersVueProjectWhenMultipleTokensAppear() {
        String raw = "候选包括 HTML 与 MULTI_FILE，最终选择 VUE_PROJECT。";
        assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                AiCodeGenTypeRoutingServiceImpl.parseCodeGenType(raw));
    }
}
