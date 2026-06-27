# 前端页面重新规划（CreatAi Frontend）

> 状态：规划中（待评审） · 范围：仅 `frontend/src`，后端接口以现状为准。
> 目标：补齐缺失页面、修复死链导航、理顺「创作 → 管理 → 展示」三条主线。

## 1. 现状

**路由（6 类）**：`/` 首页、`/app/chat/:id` 对话页、`/admin/*` 管理端 5 页、`/user/login`、`/user/register`、未知路由 redirect `/`。

**已封装 API**：
- `app.ts`：add / get/vo / my-list / good-list / update / delete / deploy / download / preview
- `user.ts`：register / login / get/login / logout
- `chatHistory.ts`：对话历史游标分页
- `admin.ts`：用户 / 应用 / 对话 / 指纹 管理接口

## 2. 问题诊断

| # | 问题 | 关联文件 |
|---|------|---------|
| P1 | 侧边栏「我的作品 `/?tab=mine`」「精选应用 `/?tab=good`」死链，HomePage 未消费 `query.tab` | `layouts/BasicLayout.vue` 41-52 |
| P2 | 无独立「我的作品」管理页：首页仅展示前 12 条、无分页、无重命名/删除入口（`updateApp`/`deleteApp` 无承载） | `api/app.ts` |
| P3 | 无「个人中心」页，登录用户无处看资料 | — |
| P4 | 无公开「应用展示/分享」只读页，精选应用直接进对话页 | `pages/AppChatPage.vue` |
| P5 | 首页 4 个工具 chip（应用类型/上传参考/风格偏好/深度模式）为无功能装饰位 | `pages/HomePage.vue` 20-25 |
| P6 | 无 404 页，未知路由裸 redirect 无感知 | `router/index.ts` 66-69 |

**后端能力缺口（前端无法独立补齐）**：`UserController` 的 save/update/add/delete 全为 `@AuthCheck(admin)`，无「当前用户改自己资料」接口。→ 个人中心**先只读**，待后端开放 `/user/update/me` 再加编辑。

## 3. 目标页面架构

```
BasicLayout（登录用户）
├── /                  首页 HomePage（创作入口 + 精选/我的预览）
├── /apps              我的作品 MyAppsPage（分页+搜索+重命名/删除）   ← 新增
├── /explore           精选应用 ExplorePage（分页浏览）             ← 新增
├── /app/chat/:id      应用对话 AppChatPage（创作工作台，保持不重写）
├── /app/share/:id     应用展示 AppSharePage（只读预览+部署链接）     ← 新增
└── /me                个人中心 ProfilePage（资料只读+我的应用快捷）  ← 新增
AdminLayout：维持现有 5 页
公开：/user/login、/user/register、/:pathMatch(.*)* → NotFoundPage  ← 新增
```

## 4. 各页面职责与接口映射

- **HomePage（改造，P1/P5）**：移除/替换装饰 chip 为真实生成模式选择；「我的作品/精选」改为跳 `/apps`、`/explore`，首页只留少量预览 + 「查看全部」。接口：`addApp`/`listMyAppByPage`/`listGoodAppByPage`。
- **MyAppsPage（新增，P2）**：分页卡片/表格，按 `appName` 搜索、`codeGenType` 过滤；行操作=进入对话 / 重命名(`updateApp`) / 删除(`deleteApp` 二次确认) / 查看展示。
- **ExplorePage（新增，P1）**：精选应用分页网格，点击进入展示页或对话页。接口：`listGoodAppByPage`/`getAppVOById`。
- **AppSharePage（新增，P4）**：只读展示应用信息 + 预览 iframe(`buildPreviewUrl`) + 部署链接(`deployUrl`)；非所有者隐藏编辑入口。接口：`getAppVOById`。
- **ProfilePage（新增，P3）**：当前登录用户资料只读（头像/昵称/账号/简介/注册时间）+ 我的应用快捷入口；编辑能力待后端接口。数据源：`loginUser` store。
- **NotFoundPage（新增，P6）**：404 友好页 + 返回首页。

## 5. 导航与布局调整

- `BasicLayout` 侧边栏：`首页 /`、`我的作品 /apps`、`精选应用 /explore`，底部用户下拉新增「个人中心 /me」。修复 P1 死链。
- 用户下拉保持「管理后台（admin）/退出登录」。

## 6. 实施阶段（建议顺序）

1. **路由与导航骨架**：新增 5 条路由 + 占位页，修复 `BasicLayout` 死链与 `/me` 入口。
2. **MyAppsPage**：完整分页 + 增删改（复用 `AppCard`）。
3. **ExplorePage**：精选分页浏览。
4. **AppSharePage**：只读展示 + 预览/部署链接。
5. **ProfilePage（只读）+ NotFoundPage**。
6. **HomePage 改造**：装饰 chip 处理 + 预览区「查看全部」跳转。
7. 类型检查 `vue-tsc` + lint 自检。

## 7. 约束

- 复用现有设计 token 与 `AppCard` 组件，风格统一。
- 不重写 `AppChatPage`（仅按需接入展示页跳转）。
- 不改后端接口；涉及后端缺口（用户自助改资料）仅标注，不强行实现。
- 删除/部署等危险操作均二次确认 + 所有者校验。
