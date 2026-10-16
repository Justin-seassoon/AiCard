package com.aicard.docgen;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

/** 低层 docx 渲染：标题 + 正文行 + 尾注（POI XWPF）。确定性，无 LLM。 */
@Component
public class DocxRenderer {

    /** 渲染 docx：居中标题 → 逐行正文 → 末尾斜体尾注（{@code note} 为 null 则不加）。 */
    public byte[] render(String title, List<String> lines, String note) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XWPFParagraph date = doc.createParagraph();
            date.setAlignment(ParagraphAlignment.RIGHT);
            date.createRun().setText(LocalDate.now().toString());

            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = titlePara.createRun();
            tr.setBold(true);
            tr.setFontSize(16);
            tr.setText(title == null ? "" : title);

            for (String line : lines) {
                doc.createParagraph().createRun().setText(line == null ? "" : line);
            }

            if (note != null && !note.isBlank()) {
                XWPFRun nr = doc.createParagraph().createRun();
                nr.setItalic(true);
                nr.setColor("C2462E");
                nr.setText(note);
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("docx 生成失败", e);
        }
    }
}
