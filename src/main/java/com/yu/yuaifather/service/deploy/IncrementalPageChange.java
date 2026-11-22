package com.yu.yuaifather.service.deploy;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 从增量编辑 SSE 片段判断这次修改是否写出了新页面。
 * <p>
 * 只读目录或替换未命中不算新页面；出现错误片段则整次编辑不自动部署，避免把半成品当成发布结果。
 */
public final class IncrementalPageChange {

    private boolean failed;
    private boolean changed;

    public void observe(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        JSONObject message;
        try {
            message = JSONUtil.parseObj(chunk);
        } catch (Exception ignored) {
            return;
        }
        String type = message.getStr("type");
        if ("error".equals(type)) {
            failed = true;
            return;
        }
        if (!"tool_executed".equals(type)) {
            return;
        }
        if (isSuccessfulPageWrite(message.getStr("name"), message.getStr("result"))) {
            changed = true;
        }
    }

    /**
     * 文件确实被改写，且本次流没有错误片段。
     */
    public boolean shouldAutoDeploy() {
        return changed && !failed;
    }

    /**
     * 与文件工具的成功返回文案对齐：未修改、警告和失败都不算新页面。
     */
    static boolean isSuccessfulPageWrite(String toolName, String result) {
        if (toolName == null || result == null || result.isBlank()) {
            return false;
        }
        String successPrefix = switch (toolName) {
            case "modifyFile" -> "文件修改成功";
            case "writeFile" -> "文件写入成功";
            case "deleteFile" -> "文件删除成功";
            default -> null;
        };
        return successPrefix != null && result.startsWith(successPrefix);
    }
}
