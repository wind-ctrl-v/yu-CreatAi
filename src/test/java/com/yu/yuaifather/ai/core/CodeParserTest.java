package com.yu.yuaifather.ai.core;

import com.yu.yuaifather.ai.core.parser.CodeParserExecutor;
import com.yu.yuaifather.ai.enums.CodeGenTypeEnum;
import com.yu.yuaifather.ai.model.HtmlCodeResult;
import com.yu.yuaifather.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodeParserTest {

    @Test
    void parseHtmlCode() {
        String codeContent = """
                随便写一段描述：
                html 格式
                <!DOCTYPE html>
                <html>
                <head>
                    <title>测试页面</title>
                </head>
                <body>
                    <h1>Hello World!</h1>
                </body>
                </html>

                随便写一段描述
                """;
        HtmlCodeResult result = (HtmlCodeResult) CodeParserExecutor.executeParser(codeContent, CodeGenTypeEnum.HTML);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
    }

    @Test
    void parseMultiFileCode() {
        String codeContent = """
                创建一个完整的网页：
                html 格式
                <!DOCTYPE html>
                <html>
                <head>
                    <title>多文件示例</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <h1>欢迎使用</h1>
                    <script src="script.js"></script>
                </body>
                </html>

                css 格式
                h1 {
                    color: blue;
                    text-align: center;
                }
                ```
                ```js
                console.log('页面加载完成');

                文件创建完成！
                """;
        MultiFileCodeResult result = (MultiFileCodeResult) CodeParserExecutor.executeParser(codeContent, CodeGenTypeEnum.MULTI_FILE);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
    }
}
