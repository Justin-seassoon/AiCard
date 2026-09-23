package com.aicard.ota.controller;

import com.aicard.ota.dto.OtaReleaseDto;
import com.aicard.ota.dto.OtaReleaseRequest;
import com.aicard.ota.service.OtaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 后台发布/列表/撤销（平台级，不绑租户）。 */
@RestController
@RequestMapping("/api/ota/releases")
public class OtaReleaseController {

    private final OtaService ota;

    public OtaReleaseController(OtaService ota) {
        this.ota = ota;
    }

    @PostMapping
    public OtaReleaseDto publish(@RequestBody OtaReleaseRequest req) {
        return ota.publish(req);
    }

    @GetMapping
    public List<OtaReleaseDto> list() {
        return ota.list();
    }

    @PostMapping("/{id}/revoke")
    public OtaReleaseDto revoke(@PathVariable Long id) {
        return ota.revoke(id);
    }
}
