package com.aicard.ota.controller;

import com.aicard.ota.OtaException;
import com.aicard.ota.service.OtaFileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/** 后台固件上传：端侧开发上传 P4/C5 固件，返回 storageKey + 实际 size/sha256，供发布表单复用。平台级，不绑租户。 */
@RestController
@RequestMapping("/api/ota/files")
public class OtaUploadController {

    private static final Set<String> MODULES = Set.of("p4", "c5");

    private final OtaFileService files;

    public OtaUploadController(OtaFileService files) {
        this.files = files;
    }

    public record UploadResult(String storageKey, long imageSize, String sha256) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@RequestParam("module") String module,
                               @RequestParam("file") MultipartFile file) {
        if (module == null || !MODULES.contains(module.trim().toLowerCase())) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "module must be p4 or c5");
        }
        if (file == null || file.isEmpty()) {
            throw new OtaException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "file is required");
        }
        String m = module.trim().toLowerCase();
        String storageKey;
        try {
            storageKey = files.store(m, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new OtaException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "failed to read upload");
        }
        return new UploadResult(storageKey, files.fileSize(storageKey), files.sha256(storageKey));
    }
}
