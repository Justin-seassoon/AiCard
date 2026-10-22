package com.aicard.docgen;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.docgen.model.ParsedWorkbook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文档读取（③ A）：上传 xlsx / xls / csv → 解析为结构化（多 sheet / 合并表头 / 备注 / 和暦等）→ 存结构化结果。
 * 文件内容按**不可信数据**清洗；只解析，不落原始文件。
 */
@RestController
@RequestMapping("/api/documents")
public class DocUploadController {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("xlsx", "xls", "csv");

    private final SpreadsheetParser parser;
    private final UploadStore store;
    private final ObjectMapper mapper;

    public DocUploadController(SpreadsheetParser parser, UploadStore store, ObjectMapper mapper) {
        this.parser = parser;
        this.store = store;
        this.mapper = mapper;
    }

    @PostMapping("/parse")
    public ParsedWorkbook parse(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file required");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过 10MB");
        }
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 xlsx / xls / csv");
        }

        ParsedWorkbook parsed = parser.parse(file.getInputStream(), filename);
        Tenant t = TenantContext.require();
        store.save(t.customerId(), t.storeId(), filename, file.getContentType(),
                mapper.writeValueAsString(parsed), mapper.writeValueAsString(parsed.warnings()));
        return parsed;
    }

    @GetMapping("/uploads")
    public List<UploadStore.StoredUpload> uploads() {
        Tenant t = TenantContext.require();
        return store.list(t.customerId(), t.storeId(), 50);
    }

    @GetMapping("/uploads/{id}")
    public Object upload(@PathVariable Long id) throws Exception {
        Tenant t = TenantContext.require();
        UploadStore.StoredUpload u = store.find(id, t.customerId(), t.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "upload not found"));
        return mapper.readTree(u.parsed() == null ? "{}" : u.parsed());
    }
}
