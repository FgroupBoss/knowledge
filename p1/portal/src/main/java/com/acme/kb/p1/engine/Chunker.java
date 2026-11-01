package com.acme.kb.p1.engine;

import com.acme.kb.p1.engine.parsing.ParsedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长滑窗 + 重叠切分；窗口在句读/换行处收尾，避免把一句话切断。
 * char_start/char_end 相对"块文本按序以换行拼接"的全文，引用预览据此定位。
 */
public final class Chunker {

    public static final String CHUNKER_ID = "sliding-window-utf8-v1";
    private static final String[] BREAKS = {"\n", "。", "！", "？", "；", ".", ";", "!", "?"};

    private Chunker() {
    }

    public static List<Chunk> chunk(ParsedDocument doc, int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        int safeOverlap = Math.max(0, Math.min(overlap, size / 2));
        List<Chunk> out = new ArrayList<>();
        int blockStart = 0;
        int chunkNo = 0;
        for (ParsedDocument.Block block : doc.blocks()) {
            String text = block.text();
            int start = 0;
            while (start < text.length()) {
                int rawEnd = Math.min(text.length(), start + size);
                int end = snap(text, start, rawEnd);
                String piece = text.substring(start, end).strip();
                if (!piece.isEmpty()) {
                    int lead = text.substring(start, end).length() - text.substring(start, end).stripLeading().length();
                    int globalStart = blockStart + start + lead;
                    out.add(new Chunk(chunkNo++, block.section(), block.pageNo(), globalStart,
                            globalStart + piece.length(), piece, Chunk.estimateTokens(piece)));
                }
                if (end >= text.length()) {
                    break;
                }
                start = Math.max(end - safeOverlap, start + 1);
            }
            blockStart += text.length() + 1;
        }
        return out;
    }

    private static int snap(String text, int start, int rawEnd) {
        if (rawEnd >= text.length()) {
            return text.length();
        }
        int earliest = start + Math.min(120, rawEnd - start);
        for (String br : BREAKS) {
            int idx = text.lastIndexOf(br, rawEnd - 1);
            if (idx >= earliest && idx + br.length() <= rawEnd) {
                return idx + br.length();
            }
        }
        return rawEnd;
    }
}
