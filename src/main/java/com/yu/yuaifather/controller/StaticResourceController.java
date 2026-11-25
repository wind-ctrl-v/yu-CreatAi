package com.yu.yuaifather.controller;


import com.yu.yuaifather.constant.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@RestController
@RequestMapping("/static")
public class StaticResourceController {

    // 应用生成根目录（用于浏览）
    private static final String PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    /**
     * 可视化元素选择器脚本，在 HTML 响应中注入到</body> 前。
     * 与前端 visualEditor.ts 通过 postMessage 协议通信：
     *   主页面 → iframe: creatai:picker-enable / creatai:picker-disable
     *   iframe → 主页面: creatai:element-picked (payload: PickedElement)
     */
    private static final String PICKER_SCRIPT = """
            <script>
            (function() {
              var _pickerEnabled = false;
              var _hoveredEl = null;
              var HOVER_CLASS = '__ve_hover__';
              var SELECT_CLASS = '__ve_selected__';

              function buildSelector(el) {
                var parts = [];
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement && parts.length < 6) {
                  var tag = cur.tagName.toLowerCase();
                  var id = cur.id ? '#' + cur.id.replace(/[^a-zA-Z0-9_-]/g, '') : '';
                  // 过滤掉 picker 注入的辅助 class，避免污染选择器
                  var classes = Array.from(cur.classList || [])
                    .filter(function(c) { return c !== HOVER_CLASS && c !== SELECT_CLASS; })
                    .map(function(c) { return '.' + c; })
                    .join('');
                  parts.unshift(tag + (id || classes));
                  cur = cur.parentElement;
                }
                return parts.join(' > ');
              }

              function onHover(e) {
                if (!_pickerEnabled) return;
                var el = e.target;
                if (_hoveredEl && _hoveredEl !== el) _hoveredEl.classList.remove(HOVER_CLASS);
                _hoveredEl = el;
                el.classList.add(HOVER_CLASS);
              }

              function onLeave(e) {
                if (e.target && e.target.classList) e.target.classList.remove(HOVER_CLASS);
              }

              function onClick(e) {
                if (!_pickerEnabled) return;
                e.preventDefault();
                e.stopPropagation();
                var el = e.target;
                // 清除上一个选中标记
                var prev = document.querySelector('.' + SELECT_CLASS);
                if (prev) prev.classList.remove(SELECT_CLASS);
                el.classList.add(SELECT_CLASS);

                var payload = {
                  tagName: el.tagName.toLowerCase(),
                  id: el.id || undefined,
                  className: el.className && typeof el.className === 'string'
                    ? el.className.replace(new RegExp('\\\\b' + HOVER_CLASS + '\\\\b|\\\\b' + SELECT_CLASS + '\\\\b', 'g'), '').trim()
                    : undefined,
                  selector: buildSelector(el),
                  text: (el.innerText || '').slice(0, 80) || undefined,
                  htmlSnippet: (el.outerHTML || '').slice(0, 600) || undefined
                };
                // 移除 undefined 字段
                Object.keys(payload).forEach(function(k) { if (payload[k] === undefined) delete payload[k]; });

                window.parent.postMessage({ type: 'creatai:element-picked', payload: payload }, '*');
              }

              function enablePicker() {
                _pickerEnabled = true;
                document.body.style.cursor = 'crosshair';
                document.addEventListener('mouseover', onHover, true);
                document.addEventListener('mouseleave', onLeave, true);
                document.addEventListener('click', onClick, true);
              }

              function disablePicker() {
                _pickerEnabled = false;
                document.body.style.cursor = '';
                document.removeEventListener('mouseover', onHover, true);
                document.removeEventListener('mouseleave', onLeave, true);
                document.removeEventListener('click', onClick, true);
                if (_hoveredEl) { _hoveredEl.classList.remove(HOVER_CLASS); _hoveredEl = null; }
                var sel = document.querySelector('.' + SELECT_CLASS);
                if (sel) sel.classList.remove(SELECT_CLASS);
              }

              window.addEventListener('message', function(e) {
                if (!e.data || typeof e.data.type !== 'string') return;
                if (e.data.type === 'creatai:picker-enable') enablePicker();
                else if (e.data.type === 'creatai:picker-disable') disablePicker();
              });

              // 默认注入 hover/selected 样式
              var style = document.createElement('style');
              style.textContent = [
                '.__ve_hover__ { outline: 2px dashed #6366f1 !important; outline-offset: 1px !important; cursor: crosshair !important; }',
                '.__ve_selected__ { outline: 2px solid #6366f1 !important; outline-offset: 1px !important; background-color: rgba(99,102,241,0.06) !important; }'
              ].join('\\n');
              document.head.appendChild(style);
            })();
            </script>
            """;

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:8123/api/static/{deployKey}[/{fileName}]
     * 对HTML 文件会在 </body> 前注入可视化选元素协议脚本。
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<?> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request) {
        try {
            // 获取资源路径
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());
            // 如果是目录访问（不带斜杠），重定向到带斜杠的URL
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            // 默认返回 index.html
            if (resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            // 计算部署目录根路径（绝对、规范化）
            Path deployRoot = Paths.get(PREVIEW_ROOT_DIR, deployKey).toAbsolutePath().normalize();
            // 如果目录下存在 dist 子目录（Vue 项目构建产物），自动使用 dist 作为根目录
            File distDir = deployRoot.resolve("dist").toFile();
            if (distDir.exists() && distDir.isDirectory()) {
                deployRoot = deployRoot.resolve("dist");
            }
            // 去掉前导斜杠后基于部署目录解析，并规范化以消除 ../ 等片段
            String relativePath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            Path filePath = deployRoot.resolve(relativePath).normalize();
            // 安全校验：解析后的路径必须仍位于部署目录之内，防止路径遍历攻击
            if (!filePath.startsWith(deployRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            File file = filePath.toFile();
            // 检查文件是否存在
            if (!file.exists() || !file.isFile()) {
                return ResponseEntity.notFound().build();
            }

            // HTML 文件：注入 picker 脚本后以字符串返回，其余文件直接返回文件资源
            if (file.getName().endsWith(".html")) {
                return serveHtmlWithPickerScript(file);
            }
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header("Content-Type", contentType(file.getPath()))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 读取 HTML 文件内容，在 {@code </body>} 标签前注入 picker 脚本后返回。
     * 如果文件不含 {@code </body>}，则将脚本追加到文件末尾（兼容手写HTML片段）。
     */
    private ResponseEntity<String> serveHtmlWithPickerScript(File file) throws IOException {
        String html = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String injected;
        int idx = html.lastIndexOf("</body>");
        if (idx >= 0) {
            injected = html.substring(0, idx) + PICKER_SCRIPT + html.substring(idx);
        } else {
            injected = html + PICKER_SCRIPT;
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/html; charset=UTF-8")
                .body(injected);
    }

    /**
     * 根据文件扩展名返回 Content-Type。位图必须带图片类型，否则浏览器会把转存下来的
     * webp / gif 当成下载流，页面里仍然显示裂图。SVG 保持二进制，避免同域脚本。
     */
    static String contentType(String filePath) {
        String path = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".avif")) return "image/avif";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
