package com.cliproxy.core.metrics;

/**
 * 多语言 Token 估算器：
 * 参考太墟 (TaiXu) 工业级启发式字符权重算法。
 * 在大模型服务商未在流式响应中回传 usage.total_tokens 时，提供高拟真度的中英文 Token 统计。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 针对文本内容进行多语言 Token 估算：
     * - 中日韩汉字 (CJK) 字符：每 1.8 字符约合 1 Token
     * - 英文与数字 (ASCII)：每 2.5 字符约合 1 Token
     * - 标点符号与特殊字符：每 2.8 字符约合 1 Token
     * - 纯空白符（空格、换行、制表符）：不计入有效内容
     */
    public static long estimateTokens(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }

        long cjk = 0;
        long ascii = 0;
        long punctuation = 0;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);
            int code = (int) ch;
            if ((code >= 0x2E80 && code <= 0x9FFF) || (code >= 0xAC00 && code <= 0xD7AF)) {
                cjk++;
            } else if (Character.isWhitespace(ch)) {
                // 空白字符忽略
            } else if ((code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z') || (code >= '0' && code <= '9')) {
                ascii++;
            } else {
                punctuation++;
            }
        }

        long estimated = (long) (cjk / 1.8f + ascii / 2.5f + punctuation / 2.8f);
        return Math.max(1, estimated);
    }

    /**
     * 针对流式 SSE 数据块或原始字节流进行保守 Token 折算
     */
    public static long estimateFromByteLength(long bodyBytes) {
        if (bodyBytes <= 0) return 0;
        return Math.max(1, bodyBytes / 4);
    }
}
