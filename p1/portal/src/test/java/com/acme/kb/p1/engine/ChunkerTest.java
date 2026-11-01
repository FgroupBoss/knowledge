package com.acme.kb.p1.engine;

import com.acme.kb.p1.engine.parsing.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引用预览依赖 char_start/char_end，偏移必须能还原出块文本本身。 */
class ChunkerTest {

    @Test
    void offsetsPointAtChunkTextInJoinedDocument() {
        String first = "第一段内容。".repeat(160);
        String second = "Second block with some words. ".repeat(80);
        ParsedDocument doc = new ParsedDocument("test",
                List.of(new ParsedDocument.Block("A", 1, first),
                        new ParsedDocument.Block("B", 2, second)),
                Map.of());

        List<Chunk> chunks = Chunker.chunk(doc, 300, 60);
        assertFalse(chunks.isEmpty());
        String joined = first + "\n" + second;
        for (Chunk chunk : chunks) {
            assertEquals(chunk.text(), joined.substring(chunk.charStart(), chunk.charEnd()), "块偏移必须自洽");
            assertTrue(chunk.charEnd() > chunk.charStart());
        }
        assertEquals(0, chunks.get(0).chunkNo());
        assertEquals(1, chunks.get(0).pageNo());
        assertEquals("A", chunks.get(0).section());
    }

    @Test
    void overlapProducesMoreChunksThanNonOverlapping() {
        String text = "x".repeat(1000);
        ParsedDocument doc = new ParsedDocument("test",
                List.of(new ParsedDocument.Block(null, null, text)), Map.of());
        int noOverlap = Chunker.chunk(doc, 200, 0).size();
        int withOverlap = Chunker.chunk(doc, 200, 50).size();
        assertTrue(withOverlap > noOverlap);
    }

    @Test
    void prefersSentenceBoundaryWithinWindow() {
        String text = "a".repeat(130) + "。" + "b".repeat(100);
        ParsedDocument doc = new ParsedDocument("test",
                List.of(new ParsedDocument.Block(null, null, text)), Map.of());
        List<Chunk> chunks = Chunker.chunk(doc, 150, 0);
        assertTrue(chunks.get(0).text().endsWith("。"), "窗口应在句读处收尾");
    }

    @Test
    void rejectsNonPositiveSize() {
        ParsedDocument doc = new ParsedDocument("test", List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> Chunker.chunk(doc, 0, 0));
    }
}
