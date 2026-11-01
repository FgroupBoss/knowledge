package com.acme.kb.p0.engine;

/**
 * 切分结果保留 char_start/char_end（相对解析后全文）与页码/章节，
 * 引用定位与二次复核依赖这些列，不允许丢失。
 */
public record Chunk(int chunkNo, String section, Integer pageNo, int charStart, int charEnd,
                    String text, int tokenEstimate) {

    public static int estimateTokens(String text) {
        int cjk = 0;
        int word = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    word++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return cjk + word;
    }
}
