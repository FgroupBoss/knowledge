package com.acme.kb.p0.engine.parsing;

public interface DocumentParser {

    ParsedDocument parse(byte[] bytes, String displayName, String mimeType);

    /** 该解析器是否覆盖此扩展名；用于输出 P0 格式能力矩阵。 */
    boolean supports(String extension);
}
