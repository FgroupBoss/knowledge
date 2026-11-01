package com.acme.kb.p1.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端行级差异（D-07）：前端不自行算"真相"，两边草稿冲突时只给差异与摘要，不做自动合并。
 *
 * 预算控制：LCS 是 O(n×m)，200 KiB 正文可能上千行，因此超过格数上限就退化成"只给计数不给行"
 * （契约 `WikiDiff.truncated`），宁可少给信息也不让一次预览请求打满堆。
 */
public final class WikiDiff {

    public record Line(String op, String text) {
    }

    public record Result(int addedLines, int removedLines, boolean truncated, List<Line> lines) {
    }

    private static final int MAX_LCS_CELLS = 1_500_000;
    private static final int MAX_OUTPUT_LINES = 400;

    private WikiDiff() {
    }

    public static Result of(String fromBody, String toBody) {
        List<String> from = lines(fromBody);
        List<String> to = lines(toBody);
        int added = countDelta(to, from);
        int removed = countDelta(from, to);
        if ((long) from.size() * to.size() > MAX_LCS_CELLS) {
            return new Result(added, removed, true, List.of());
        }
        List<Line> diff = lcs(from, to);
        boolean truncated = diff.size() > MAX_OUTPUT_LINES;
        return new Result(added, removed, truncated, truncated ? List.of() : diff);
    }

    private static List<String> lines(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : body.split("\r?\n", -1)) {
            out.add(line);
        }
        return out;
    }

    /** 多重集合差：只统计新增/删除行数，不受行序变化影响，成本 O(n)。 */
    private static int countDelta(List<String> target, List<String> base) {
        Map<String, Integer> counts = new HashMap<>();
        base.forEach(line -> counts.merge(line, 1, Integer::sum));
        int delta = 0;
        for (String line : target) {
            int left = counts.getOrDefault(line, 0);
            if (left > 0) {
                counts.put(line, left - 1);
            } else {
                delta++;
            }
        }
        return delta;
    }

    private static List<Line> lcs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Line("context", a.get(i)));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new Line("removed", a.get(i)));
                i++;
            } else {
                out.add(new Line("added", b.get(j)));
                j++;
            }
        }
        while (i < n) {
            out.add(new Line("removed", a.get(i++)));
        }
        while (j < m) {
            out.add(new Line("added", b.get(j++)));
        }
        return out;
    }
}
