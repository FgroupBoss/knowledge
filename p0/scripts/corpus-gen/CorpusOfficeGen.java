import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 语料生成器（office 类）：把 office-spec.json 渲染成真实的 docx/xlsx/pptx/pdf。
 * 与门户参考解析器同一套 POI/PDFBox 版本，保证「生成端与解析端不同实现」这一前提成立。
 * 额外产出 OPS-16.pdf：只有位图、没有文字层的扫描件，用来固化 OCR 缺口证据。
 */
public final class CorpusOfficeGen {

    public static void main(String[] args) throws Exception {
        Path spec = Path.of(args[0]);
        Path corpusDir = Path.of(args[1]);
        String fontPath = args.length > 2 ? args[2] : "C:/Windows/Fonts/simhei.ttf";
        JsonNode root = new ObjectMapper().readTree(Files.readString(spec));
        int made = 0;
        for (JsonNode node : root) {
            String format = node.get("format").asText();
            File out = new File(node.get("path").asText());
            out.getParentFile().mkdirs();
            switch (format) {
                case "docx" -> writeDocx(node, out);
                case "xlsx" -> writeXlsx(node, out);
                case "pptx" -> writePptx(node, out);
                case "pdf" -> writePdf(node, out, fontPath);
                default -> throw new IllegalStateException("unknown format " + format);
            }
            made++;
        }
        File pdfDir = corpusDir.resolve("pdf").toFile();
        pdfDir.mkdirs();
        writeScannedPdf(new File(pdfDir, "OPS-16.pdf"));
        System.out.println("office 语料 " + made + " 份 + 扫描 PDF 1 份，输出目录 " + corpusDir);
    }

    private static List<String> linesOf(JsonNode block) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : block.get("lines")) {
            out.add(n.asText());
        }
        return out;
    }

    private static void writeDocx(JsonNode node, File out) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            title.setStyle("Title");
            title.createRun().setText(node.get("title").asText());
            XWPFParagraph meta = doc.createParagraph();
            meta.createRun().setText(node.get("meta").asText());
            for (JsonNode block : node.get("blocks")) {
                XWPFParagraph head = doc.createParagraph();
                head.setStyle("Heading1");
                head.createRun().setText(block.get("heading").asText());
                for (String line : linesOf(block)) {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().setText(line);
                }
            }
            XWPFParagraph head = doc.createParagraph();
            head.setStyle("Heading1");
            head.createRun().setText("7 参数登记");
            XWPFTable table = doc.createTable(4, 4);
            String[][] cells = {
                {"主体", "属性", "规定值", "备注"},
                {param(node, 0, "subject"), param(node, 0, "attribute"), param(node, 0, "value"), "本文件强制项"},
                {param(node, 1, "subject"), param(node, 1, "attribute"), param(node, 1, "value"), "参考项"},
                {"适用范围", "生效日期", node.get("code").asText(), node.get("blocks").size() + " 节"},
            };
            for (int r = 0; r < cells.length; r++) {
                XWPFTableRow row = table.getRow(r);
                for (int c = 0; c < cells[r].length; c++) {
                    row.getCell(c).setText(cells[r][c]);
                }
            }
            try (OutputStream os = new FileOutputStream(out)) {
                doc.write(os);
            }
        }
    }

    /** 参数由 spec 的 params 数组给出；缺项说明 spec 与语料表不同步，宁可留空也不编造。 */
    private static String param(JsonNode node, int idx, String key) {
        JsonNode params = node.path("params");
        if (params.isArray() && params.size() > idx && params.get(idx).has(key)) {
            return params.get(idx).get(key).asText();
        }
        throw new IllegalStateException("office-spec 缺少 params：" + node.get("code").asText());
    }

    private static void writeXlsx(JsonNode node, File out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet info = wb.createSheet(node.get("sheet").asText() + "参数");
            int r = 0;
            info.createRow(r++).createCell(0).setCellValue(node.get("title").asText());
            info.createRow(r++).createCell(0).setCellValue(node.get("meta").asText());
            XSSFRow header = info.createRow(r++);
            for (int c = 0; c < 4; c++) {
                header.createCell(c).setCellValue(new String[]{"主体", "属性", "规定值", "出处"}[c]);
            }
            for (String line : paramLines(node)) {
                XSSFRow row = info.createRow(r++);
                row.createCell(0).setCellValue(line);
                row.createCell(3).setCellValue(node.get("code").asText() + " 第 3 节");
            }
            XSSFSheet body = wb.createSheet("正文");
            int br = 0;
            for (JsonNode block : node.get("blocks")) {
                body.createRow(br++).createCell(0).setCellValue(block.get("heading").asText());
                for (String line : linesOf(block)) {
                    XSSFRow row = body.createRow(br++);
                    row.createCell(0).setCellValue(line);
                }
            }
            try (OutputStream os = new FileOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    /** 从「3 关键参数规定」小节取带「」的两句，作为工作簿的参数行。 */
    private static List<String> paramLines(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode block : node.get("blocks")) {
            if (!block.get("heading").asText().startsWith("3")) {
                continue;
            }
            for (String line : linesOf(block)) {
                if (line.contains("」")) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    private static void writePptx(JsonNode node, File out) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(720, 540));
            XSLFSlide cover = ppt.createSlide();
            text(cover, 40, 40, 640, 70, node.get("title").asText());
            text(cover, 40, 120, 640, 50, node.get("meta").asText());
            for (JsonNode block : node.get("blocks")) {
                XSLFSlide slide = ppt.createSlide();
                text(slide, 40, 30, 640, 50, block.get("heading").asText());
                StringBuilder body = new StringBuilder();
                for (String line : linesOf(block)) {
                    body.append("• ").append(line).append('\n');
                }
                text(slide, 40, 100, 640, 380, body.toString());
            }
            XSLFSlide end = ppt.createSlide();
            text(end, 40, 30, 640, 50, "依据与追溯");
            text(end, 40, 100, 640, 300, "本件编号 " + node.get("code").asText()
                    + "。参数以受控文本为准，讲解稿不得作为独立依据。\n" + String.join("\n", paramLines(node)));
            try (OutputStream os = new FileOutputStream(out)) {
                ppt.write(os);
            }
        }
    }

    private static void text(XSLFSlide slide, int x, int y, int w, int h, String content) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.Rectangle(x, y, w, h));
        box.setText(content);
    }

    private static void writePdf(JsonNode node, File out, String fontPath) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, new FileInputStream(new File(fontPath)), true);
            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float margin = 56f;
            float body = 11f;
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = pageH - margin;
            y = line(doc, cs, font, pageW, margin, y, 16f, node.get("title").asText());
            y = line(doc, cs, font, pageW, margin, y, body, node.get("meta").asText());
            y -= 8;
            for (JsonNode block : node.get("blocks")) {
                if (y < margin + 90) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = pageH - margin;
                }
                y = line(doc, cs, font, pageW, margin, y, 12.5f, block.get("heading").asText());
                for (String l : linesOf(block)) {
                    for (String piece : wrap(l, font, body, pageW - 2 * margin)) {
                        if (y < margin + 20) {
                            cs.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            cs = new PDPageContentStream(doc, page);
                            y = pageH - margin;
                        }
                        y = line(doc, cs, font, pageW, margin + 10, y, body, piece);
                    }
                    y -= 3;
                }
                y -= 8;
            }
            cs.close();
            doc.save(out);
        }
    }

    private static float line(PDDocument doc, PDPageContentStream cs, PDType0Font font, float pageW,
                              float x, float y, float size, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        return y - (size + 7f);
    }

    private static List<String> wrap(String text, PDType0Font font, float size, float width) throws Exception {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        float used = 0f;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            float w = font.getStringWidth(ch) / 1000f * size;
            if (used + w > width && !cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
                used = 0f;
            }
            cur.append(ch);
            used += w;
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 无文字层扫描件：正文只以位图存在，参考解析器必然取不到文本，这是 OCR/引擎缺口的固定证据。 */
    private static void writeScannedPdf(File out) throws Exception {
        BufferedImage img = new BufferedImage(1240, 1650, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(38, 38, 38));
        g.setFont(new Font("SimHei", Font.PLAIN, 34));
        int y = 90;
        for (String line : List.of(
                "设备点检作业指导书（扫描件）",
                "文件编号：OPS-16　生效日期：2026-08-05　归口部门：设备工程部",
                "",
                "1 目的与范围",
                "本指导书规定空压机日常点检项目与判定口径，适用于动力站房。",
                "",
                "2 点检项目",
                "排气压力的巡检标准为 0.62 兆帕，超出即报修。",
                "润滑油位每班确认一次，低于下限须补加至中位。",
                "",
                "3 记录与追溯",
                "点检记录保存两年，纸质台账为最终依据。")) {
            g.drawString(line, 60, y);
            y += 52;
        }
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, png.toByteArray(), "scan");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(out);
        }
    }
}
