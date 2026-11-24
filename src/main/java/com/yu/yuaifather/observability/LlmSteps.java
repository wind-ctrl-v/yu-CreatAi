package com.yu.yuaifather.observability;

import com.yu.yuaifather.model.enums.CodeGenTypeEnum;

/**
 * 模型调用步骤名。与 Agent / 经典链路一一对应，写入日志、SSE 和 generation_run。
 */
public final class LlmSteps {

    public static final String UNATTRIBUTED = "unattributed";
    public static final String DYNAMIC_SPEC = "DynamicSpecSynthesizer";
    public static final String INCREMENTAL_EDIT = "IncrementalEdit";
    public static final String CLASSIC_HTML = "ClassicHtml";
    public static final String CLASSIC_MULTI_FILE = "ClassicMultiFile";
    public static final String CLASSIC_VUE = "ClassicVue";

    private LlmSteps() {
    }

    public static String classic(CodeGenTypeEnum type) {
        if (type == null) {
            return "Classic";
        }
        return switch (type) {
            case HTML -> CLASSIC_HTML;
            case MULTI_FILE -> CLASSIC_MULTI_FILE;
            case VUE_PROJECT -> CLASSIC_VUE;
        };
    }
}
