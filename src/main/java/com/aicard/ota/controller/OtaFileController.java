package com.aicard.ota.controller;

import com.aicard.ota.OtaException;
import com.aicard.ota.service.OtaFileService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 固件下载：短期签名 URL，无 Bearer，靠 expires + sig 校验。 */
@RestController
@RequestMapping("/api/v1/ota/files")
public class OtaFileController {

    private final OtaFileService files;

    public OtaFileController(OtaFileService files) {
        this.files = files;
    }

    @GetMapping("/{storageKey}")
    public ResponseEntity<byte[]> download(@PathVariable String storageKey,
                                           @RequestParam("expires") long expires,
                                           @RequestParam("sig") String sig) {
        if (!files.verify(storageKey, expires, sig)) {
            throw new OtaException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid or expired url");
        }
        byte[] data = files.read(storageKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .contentLength(data.length)
                .body(data);
    }
}
