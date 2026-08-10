package com.yu.yuaifather.codegen.memory;

import com.yu.yuaifather.model.enums.CodeGenTypeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话记忆 memoryId 的唯一生成出口。
 *
 * <p>同一个 appId 下存在多套记忆实例（主链路按代码生成类型隔离、可视化增量编辑独立一套），
 * 它们各自持有独立的 JVM 内存窗口。若共用裸 appId 作为 memoryId，多实例会对同一个 Redis key
 * 做全量覆盖写，快照互相踩脏。因此 memoryId 一律携带用途后缀，并集中在此生成，
 * 避免字符串拼接散落各处后与「按 appId 清理」的入口失配。</p>
 */
public final class ChatMemoryIds {

    /** 可视化增量编辑链路的用途标识 */
    private static final String PURPOSE_EDIT = "edit";

    private static final String SEPARATOR = "#";

    private ChatMemoryIds() {
    }

    /**
     * 主生成链路：按 appId + 代码生成类型隔离
     */
    public static String forCodeGen(long appId, CodeGenTypeEnum codeGenType) {
        String purpose = codeGenType == null ? "unknown" : codeGenType.getValue();
        return appId + SEPARATOR + purpose;
    }

    /**
     * 可视化增量编辑链路
     */
    public static String forEdit(long appId) {
        return appId + SEPARATOR + PURPOSE_EDIT;
    }

    /**
     * 某个 appId 名下的全部 memoryId，用于「清理该应用所有记忆」这类整体操作。
     */
    public static List<String> allOf(long appId) {
        List<String> ids = new ArrayList<>();
        for (CodeGenTypeEnum codeGenType : CodeGenTypeEnum.values()) {
            ids.add(forCodeGen(appId, codeGenType));
        }
        ids.add(forEdit(appId));
        return ids;
    }
}
