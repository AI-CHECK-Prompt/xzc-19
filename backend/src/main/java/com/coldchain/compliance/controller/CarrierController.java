package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.service.OperationLogService;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 多承运商档案管理。
 */
@RestController
@RequestMapping("/api/carriers")
@RequiredArgsConstructor
public class CarrierController {

    private final CarrierRepository repo;
    private final OperationLogService opLogService;

    @GetMapping
    public List<Carrier> list(@RequestParam(required = false) String type) {
        if (type != null) return repo.findByCarrierType(type);
        return repo.findByEnabledTrue();
    }

    @GetMapping("/{id}")
    public Carrier get(@PathVariable Long id) {
        return repo.findById(id).orElse(null);
    }

    @PostMapping
    public Carrier create(@RequestBody Carrier c, HttpServletRequest req) {
        if (c.getEnabled() == null) c.setEnabled(true);
        Carrier saved = repo.save(c);
        opLogService.logAsync("ADMIN", "ADMIN", "CREATE_CARRIER", "CARRIER",
                saved.getCarrierCode(), "code=" + saved.getCarrierCode(), "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }
}
