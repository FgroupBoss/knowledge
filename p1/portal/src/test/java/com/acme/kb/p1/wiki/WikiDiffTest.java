package com.acme.kb.p1.wiki;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差异计算是治理动作的依据（D-07：只给差异，不做自动合并），
 * 因此两类预算退化路径必须可证：超格数只给计数、超行数也只给计数，且计数在退化时仍然准确。
 */
class WikiDiffTest {

    @Test
    void identicalBodiesYieldOnlyContextLines() {
        WikiDiff.Result r = WikiDiff.of("a\nb\nc", "a\nb\nc");
        assertEquals(0, r.addedLines());
        assertEquals(0, r.removedLines());
        assertFalse(r.truncated());
        assertEquals(List.of("a", "b", "c"), r.lines().stream().map(WikiDiff.Line::text).toList());
        assertTrue(r.lines().stream().allMatch(l -> l.op().equals("context")));
    }

    @Test
    void appendAndDeleteAreCountedAndLabelled() {
        WikiDiff.Result appended = WikiDiff.of("a\nb", "a\nb\nc");
        assertEquals(1, appended.addedLines());
        assertEquals(0, appended.removedLines());
        assertEquals(List.of("context", "context", "added"),
                appended.lines().stream().map(WikiDiff.Line::op).toList());

        WikiDiff.Result removedOne = WikiDiff.of("a\nb", "a");
        assertEquals(0, removedOne.addedLines());
        assertEquals(1, removedOne.removedLines());
        assertEquals(List.of("context", "removed"),
                removedOne.lines().stream().map(WikiDiff.Line::op).toList());
    }

    /** 计数走多重集合：重复行不会被当成"内容没变"而少算。 */
    @Test
    void duplicateLinesAreCountedPerOccurrence() {
        WikiDiff.Result dropped = WikiDiff.of("x\nx", "x");
        assertEquals(0, dropped.addedLines());
        assertEquals(1, dropped.removedLines());

        WikiDiff.Result doubled = WikiDiff.of("x", "x\nx");
        assertEquals(1, doubled.addedLines());
        assertEquals(0, doubled.removedLines());
    }

    @Test
    void crlfAndTrailingNewlineAreTreatedAsLineBreaksNotAsContent() {
        WikiDiff.Result crlf = WikiDiff.of("a\r\nb", "a\r\nc");
        assertEquals(1, crlf.addedLines());
        assertEquals(1, crlf.removedLines());
        assertEquals(List.of("context", "removed", "added"),
                crlf.lines().stream().map(WikiDiff.Line::op).toList());

        WikiDiff.Result trailing = WikiDiff.of("a\n", "a");
        assertEquals(1, trailing.removedLines());
        assertEquals(0, trailing.addedLines());
    }

    @Test
    void nullAndEmptyBodiesAreDiffable() {
        WikiDiff.Result both = WikiDiff.of(null, null);
        assertEquals(0, both.addedLines());
        assertEquals(0, both.removedLines());
        assertTrue(both.lines().isEmpty());

        WikiDiff.Result fromEmpty = WikiDiff.of("", "a\nb");
        assertEquals(2, fromEmpty.addedLines());
        assertEquals(0, fromEmpty.removedLines());
    }

    @Test
    void opsAreOnlyTheThreeContractedValues() {
        Set<String> ops = WikiDiff.of("a\nb\nc", "b\na\nd").lines().stream()
                .map(WikiDiff.Line::op).collect(Collectors.toSet());
        assertTrue(Set.of("context", "added", "removed").containsAll(ops), "出现了未知 op：" + ops);
    }

    /** 行数超预算：仍返回准确计数，只丢掉逐行内容（响应里 truncated=true）。 */
    @Test
    void outputLineBudgetDegradesToCountsOnly() {
        WikiDiff.Result r = WikiDiff.of(join(210, "f"), join(210, "t"));
        assertTrue(r.truncated());
        assertEquals(210, r.addedLines());
        assertEquals(210, r.removedLines());
        assertTrue(r.lines().isEmpty());
    }

    /** 格数超预算（LCS 是 O(n×m)）：不进入 LCS，成本恒定。 */
    @Test
    void lcsCellBudgetDegradesWithoutEnteringTheMatrix() {
        WikiDiff.Result r = WikiDiff.of(join(1300, "L"), join(1200, "L"));
        assertTrue(r.truncated());
        assertTrue(r.lines().isEmpty());
        assertEquals(0, r.addedLines());
        assertEquals(100, r.removedLines());
    }

    private static String join(int count, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(i);
        }
        return sb.toString();
    }
}
