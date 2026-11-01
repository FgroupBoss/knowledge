package com.acme.kb.p0.engine.parsing;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P0 参考解析器：覆盖文本类与 OOXML/PDF 常见办公格式。
 * 定位信息（页码、章节、字符偏移）必须与原文一致，否则引用不可用。
 * 无文字层的扫描件在此会以永久错误失败，这正是需要知识引擎/OCR 的证据。
 */
@Component
public class MultiFormatDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MultiFormatDocumentParser.class);
    private static final Set<String> TEXT_LIKE = Set.of("md", "markdown", "txt", "log", "csv", "tsv", "json");
    private static final int MAX_SHEET_ROWS = 5000;

    @Override
    public boolean supports(String extension) {
        if (extension == null) {
            return false;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        return TEXT_LIKE.contains(ext) || ext.equals("pdf") || ext.equals("docx") || ext.equals("xlsx")
                || ext.equals("pptx");
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String displayName, String mimeType) {
        String ext = extensionOf(displayName, mimeType);
        if (!supports(ext)) {
            throw new ApiException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "参考解析器不支持该格式: " + ext);
        }
        try {
            ParsedDocument parsed = switch (ext) {
                case "pdf" -> parsePdf(bytes, displayName);
                case "docx" -> parseDocx(bytes);
                case "xlsx" -> parseXlsx(bytes);
                case "pptx" -> parsePptx(bytes);
                default -> parseText(bytes, ext);
            };
            if (!parsed.hasText()) {
                throw new ApiException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                        "文档未解析出可检索文本（可能是扫描件或空文件）");
            }
            return parsed;
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("parse_failed ext={} name={} err={}", ext, displayName, e.toString());
            throw new ApiException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED,
                    "文档解析失败: " + e.getClass().getSimpleName());
        }
    }

    private ParsedDocument parsePdf(byte[] bytes, String displayName) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "PDF 已加密，无法解析");
            }
            int pages = doc.getNumberOfPages();
            meta.put("pages", pages);
            List<ParsedDocument.Block> blocks = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).strip();
                if (!text.isEmpty()) {
                    blocks.add(new ParsedDocument.Block("第 " + page + " 页", page, text));
                }
            }
            meta.put("textPages", blocks.size());
            meta.put("displayName", displayName);
            return new ParsedDocument("pdfbox-3.0.3", blocks, meta);
        }
    }

    private ParsedDocument parseDocx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<ParsedDocument.Block> blocks = new ArrayList<>();
            String section = null;
            StringBuilder buf = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText() == null ? "" : para.getText().strip();
                if (text.isEmpty()) {
                    continue;
                }
                String style = para.getStyle() == null ? "" : para.getStyle().toLowerCase(Locale.ROOT);
                if (style.startsWith("heading") || style.startsWith("标题")) {
                    if (!buf.isEmpty()) {
                        blocks.add(new ParsedDocument.Block(section, null, buf.toString().strip()));
                        buf.setLength(0);
                    }
                    section = text;
                    continue;
                }
                buf.append(text).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                StringBuilder t = new StringBuilder();
                for (XWPFTableRow row : table.getRows()) {
                    String line = row.getTableCells().stream().map(c -> c.getText().strip())
                            .reduce((a, b) -> a + " | " + b).orElse("");
                    if (!line.isBlank()) {
                        t.append(line).append('\n');
                    }
                }
                if (!t.isEmpty()) {
                    blocks.add(new ParsedDocument.Block(section == null ? "表格" : section + " 表格", null,
                            t.toString().strip()));
                }
            }
            if (!buf.isEmpty()) {
                blocks.add(new ParsedDocument.Block(section, null, buf.toString().strip()));
            }
            return new ParsedDocument("poi-ooxml-5.4.1", blocks, Map.of("tables", doc.getTables().size()));
        }
    }

    private ParsedDocument parseXlsx(byte[] bytes) throws IOException {
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                StringBuilder buf = new StringBuilder();
                int rows = 0;
                for (Row row : sheet) {
                    if (rows++ >= MAX_SHEET_ROWS) {
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String v = formatter.formatCellValue(cell).strip();
                        if (!v.isEmpty()) {
                            cells.add(v);
                        }
                    }
                    if (!cells.isEmpty()) {
                        buf.append(String.join(" | ", cells)).append('\n');
                    }
                }
                if (!buf.isEmpty()) {
                    blocks.add(new ParsedDocument.Block(sheet.getSheetName(), null, buf.toString().strip()));
                }
            }
        }
        return new ParsedDocument("poi-ooxml-5.4.1", blocks, Map.of("sheets", blocks.size()));
    }

    private ParsedDocument parsePptx(byte[] bytes) throws IOException {
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int pageNo = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                pageNo++;
                String title = null;
                StringBuilder body = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText() == null ? "" : textShape.getText().strip();
                        if (text.isEmpty()) {
                            continue;
                        }
                        if (title == null && shape.getClass().getSimpleName().contains("Title")) {
                            title = text;
                        } else {
                            body.append(text).append('\n');
                        }
                    }
                }
                String section = title == null ? "第 " + pageNo + " 页" : title;
                String content = body.toString().strip();
                if (!content.isEmpty()) {
                    blocks.add(new ParsedDocument.Block(section, pageNo, content));
                }
            }
        }
        return new ParsedDocument("poi-ooxml-5.4.1", blocks, Map.of("slides", blocks.size()));
    }

    private ParsedDocument parseText(byte[] bytes, String ext) {
        Charset charset = utf8OrGbk(bytes);
        String raw = new String(bytes, charset).replace("\r\n", "\n").replace('\r', '\n');
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        if (ext.equals("md") || ext.equals("markdown")) {
            String section = null;
            StringBuilder buf = new StringBuilder();
            for (String line : raw.split("\n", -1)) {
                if (line.startsWith("#")) {
                    if (!buf.isEmpty()) {
                        blocks.add(new ParsedDocument.Block(section, null, buf.toString().strip()));
                        buf.setLength(0);
                    }
                    section = line.replaceAll("^#+\\s*", "").strip();
                    continue;
                }
                buf.append(line).append('\n');
            }
            if (!buf.isEmpty()) {
                blocks.add(new ParsedDocument.Block(section, null, buf.toString().strip()));
            }
        } else if (ext.equals("csv") || ext.equals("tsv")) {
            String sep = ext.equals("tsv") ? "\t" : ",";
            String[] lines = raw.split("\n");
            String header = lines.length > 0 ? lines[0].strip() : "";
            StringBuilder buf = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) {
                    continue;
                }
                buf.append(header).append('\n').append(line).append('\n');
                if (i % 20 == 0) {
                    blocks.add(new ParsedDocument.Block(header.isBlank() ? null : header, null,
                            buf.toString().strip()));
                    buf.setLength(0);
                }
            }
            if (!buf.isEmpty()) {
                blocks.add(new ParsedDocument.Block(header.isBlank() ? null : header, null,
                        buf.toString().strip()));
            }
            if (blocks.isEmpty()) {
                blocks.add(new ParsedDocument.Block(null, null, header));
            }
        } else {
            for (String para : raw.split("\n{2,}")) {
                String text = para.strip();
                if (!text.isEmpty()) {
                    blocks.add(new ParsedDocument.Block(null, null, text));
                }
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("charset", charset.name());
        meta.put("sep", (ext.equals("csv") || ext.equals("tsv")) ? ext : "paragraph");
        return new ParsedDocument("plain-text", blocks, meta);
    }

    private Charset utf8OrGbk(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("GBK");
        }
    }

    private String extensionOf(String displayName, String mimeType) {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        return switch (mimeType == null ? "" : mimeType) {
            case "application/pdf" -> "pdf";
            case "text/csv" -> "csv";
            case "application/json" -> "json";
            default -> "txt";
        };
    }
}
