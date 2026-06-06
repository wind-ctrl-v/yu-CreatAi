# Yu Code Father · 前端

为 `yu_code_father`（AI 对话生成代码平台）后端配套的前端工程：登录/注册 → 首页（输入 prompt + 精选应用）→ 对话生成页（SSE 流式输出 + iframe 实时预览 + 部署/下载）。

## 技术栈

- Vue 3 + `<script setup>` + TypeScript
- Vite 5
- Vue Router 4（含全局登录守卫）
- Pinia（登录用户态）
- Ant Design Vue 4
- Axios（统一实例 + 响应拦截）
- 原生 EventSource（SSE 流式）

## 启动

```bash
npm install
npm run dev
# 访问 http://localhost:5173
```

> 需同时启动后端，监听 `http://localhost:8123`（context-path `/api`）。
> 开发期 Vite 会把 `/api` 代理到后端，因此前端与后端同源，Session Cookie 可正常携带。

## 与后端对接说明

- 鉴权：基于后端 Spring Session + Cookie，所有请求 `withCredentials: true` 自动携带。
- 统一响应体：`{ code, data, message }`，`code === 0` 为成功，`code === 40100` 表示未登录（自动跳转登录页）。
- SSE：`GET /api/app/chat/gen/code?appId=&message=`，每条数据为 `data: {"d": "片段"}`，结束发送 `event: done`。
- 实时预览：生成产物访问 `/api/static/{codeGenType}_{appId}/`（如 `html_123/`）；
  - `html` / `multi_file` 类型可直接预览；
  - `vue_project` 需后端构建后才能预览，部署后通过返回的 URL 访问。
- 部署：`POST /api/app/deploy`，返回可访问 URL。

## 目录结构

```text
src/
├── api/            # 接口封装 (user.ts / app.ts / chatHistory.ts)
├── request/        # axios 实例 + 响应拦截
├── stores/         # Pinia (loginUser)
├── utils/          # sse.ts (EventSource 封装)
├── router/         # 路由表 + 登录守卫
├── layouts/        # BasicLayout 顶栏布局
├── components/     # AppCard 等
├── pages/          # HomePage / AppChatPage / user/LoginPage / user/RegisterPage
├── types/          # 接口类型契约
└── styles/         # 全局样式
```
