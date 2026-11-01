package com.acme.kb.p0.engine.parsing;

import java.util.List;
import java.util.Map;

/**
 * 解析结果只包含文本块与其在原文中的定位信息（章节/页码），不含任何权限判定。
 * block 顺序即文档顺序，切分器据此计算 char_start/char_end 供引用定位。
 */
public record ParsedDocument(String parser, List<Block> blocks, Map<String, Object> meta) {

    public record Block(String section, Integer pageNo, String text) {
    }

    public boolean hasText() {
        return blocks.stream().anyMatch(b -> !b.text().isBlank());
    }

    public int textChars() {
        return blocks.stream().mapToInt(b -> b.text().length()).sum();
    }
}
