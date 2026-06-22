package com.yu.yuaifather.tool.agent;

import com.yu.yuaifather.tool.core.AgentTool;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.core.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SearchUserHistoryTool：搜索用户历史生成的页面（Agent Tool）。
 * <p>
 * 当用户说"参考我上次那个页面"时，PlanningAgent 可调用此工具查找历史记录。
 * <p>
 * 当前为占位实现，后续关联 UserHistoryService 完成实际搜索。
 */
@Slf4j
@Component
public class SearchUserHistoryTool implements AgentTool<SearchUserHistoryTool.Input, SearchUserHistoryTool.Result> {

    public static final String TOOL_NAME = "SearchUserHistoryTool";

    public record Input(String query, int limit) {}
    public record Result(List<HistoryItem> items) {}
    public record HistoryItem(Long pageId, String title, String createdAt) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("搜索用户历史生成的页面，支持自然语言查询。用户说'参考我上次那个页面'时使用。");
    }

    @Override
    public Result execute(Input input, ToolContext context) {
        log.info("SearchUserHistoryTool 查询 userId={} query={}", context.getCurrentUserId(), input.query());
        // TODO: 关联 UserHistoryService
        return new Result(List.of());
    }
}
