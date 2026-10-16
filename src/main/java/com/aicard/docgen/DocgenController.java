package com.aicard.docgen;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
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

/** ③ 文档/文书处理 下载端点：报表导出 xlsx、社内文书草稿 docx。 */
@RestController
public class DocgenController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ReportService reports;
    private final ReportXlsxWriter xlsx;
    private final BusinessDocWriter docx;

    public DocgenController(ReportService reports, ReportXlsxWriter xlsx, BusinessDocWriter docx) {
        this.reports = reports;
        this.xlsx = xlsx;
        this.docx = docx;
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

    private static ResponseEntity<byte[]> download(byte[] body, String contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    public record BusinessDocRequest(String docType, String topic, String points) {}
}
