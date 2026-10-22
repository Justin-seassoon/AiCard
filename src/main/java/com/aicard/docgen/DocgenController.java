package com.aicard.docgen;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.docgen.template.DocTemplate;
import com.aicard.docgen.template.TemplateService;
import com.aicard.report.repository.DeviceAggregate;
import com.aicard.report.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** ③ 文档/文书处理 下载端点：报表导出 xlsx、社内文书草稿 docx。 */
@RestController
public class DocgenController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ReportService reports;
    private final ReportXlsxWriter xlsx;
    private final BusinessDocWriter docx;
    private final TemplateService templates;

    public DocgenController(ReportService reports, ReportXlsxWriter xlsx, BusinessDocWriter docx,
                            TemplateService templates) {
        this.reports = reports;
        this.xlsx = xlsx;
        this.docx = docx;
        this.templates = templates;
    }

    /** 导出报表为 xlsx；{@code deviceId} 省略 = 租户级（C4）。 */
    @GetMapping("/api/reports/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@RequestParam String periodType,
                                             @RequestParam LocalDate start,
                                             @RequestParam(required = false) LocalDate end,
                                             @RequestParam(required = false) String deviceId) {
        Tenant t = TenantContext.require();
        LocalDate endDate = (end == null || !end.isAfter(start)) ? start.plusDays(1) : end;
        DeviceAggregate agg = reports.aggregate(t.customerId(), t.storeId(), deviceId, start, endDate);
        String scope = (deviceId == null || deviceId.isBlank()) ? "店舗全体" : deviceId;
        byte[] body = xlsx.write("利用レポート", scope, start + " ～ " + endDate, agg);
        return download(body, XLSX, "report-" + periodType + "-" + start + ".xlsx");
    }

    @PostMapping("/api/docgen/business-doc.docx")
    public ResponseEntity<byte[]> businessDoc(@RequestBody BusinessDocRequest req) {
        byte[] body = docx.write(req.docType(), req.topic(), req.points());
        return download(body, DOCX, "business-doc.docx");
    }

    /** ③ B：模板清单。 */
    @GetMapping("/api/docgen/templates")
    public List<TemplateInfo> templates() {
        return Arrays.stream(DocTemplate.values())
                .map(t -> new TemplateInfo(t.id(), t.label(), t.format(), t.description()))
                .toList();
    }

    /** ③ B：按模板生成文书（内容由 LLM 起草，排版由模板确定性渲染）。 */
    @PostMapping("/api/docgen/documents")
    public ResponseEntity<byte[]> document(@RequestBody DocRequest req) {
        TemplateService.Generated g = templates.generate(req.template(), req.fields());
        return download(g.bytes(), g.mime(), g.filename());
    }

    private static ResponseEntity<byte[]> download(byte[] body, String contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    public record BusinessDocRequest(String docType, String topic, String points) {}

    public record TemplateInfo(String id, String label, String format, String description) {}

    public record DocRequest(String template, Map<String, Object> fields) {}
}
