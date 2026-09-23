package com.aicard.ota.controller;

import com.aicard.ota.dto.OtaCheckRequest;
import com.aicard.ota.dto.OtaCheckResponse;
import com.aicard.ota.service.OtaService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备侧检查更新（独立 HTTPS，Bearer device_token 鉴权）。 */
@RestController
@RequestMapping("/api/v1/ota")
public class OtaCheckController {

    private final OtaService ota;

    public OtaCheckController(OtaService ota) {
        this.ota = ota;
    }

    @PostMapping("/check")
    public OtaCheckResponse check(@RequestBody OtaCheckRequest req,
                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ota.check(req, bearer(authorization));
    }

    private String bearer(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return null;
    }
}
