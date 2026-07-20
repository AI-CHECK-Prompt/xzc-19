package com.coldchain.compliance.controller;

import com.coldchain.compliance.service.ExportService;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @PostMapping("/{format}")
    public Map<String, Object> export(@PathVariable String format,
                                     @RequestParam String taskNo,
                                     @RequestParam(required = false, defaultValue = "flight_inspector") String user) throws Exception {
        String file;
        switch (format.toLowerCase()) {
            case "xml":
                file = exportService.exportXml(taskNo, user); break;
            case "pdf":
                file = exportService.exportPdf(taskNo, user); break;
            case "excel":
            case "xlsx":
                file = exportService.exportExcel(taskNo, user); break;
            default:
                throw new IllegalArgumentException("不支持的格式: " + format);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("taskNo", taskNo);
        r.put("format", format);
        r.put("file", file);
        r.put("url", "/api/export/download?path=" + file);
        return r;
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        File f = new File(path);
        if (!f.exists()) return ResponseEntity.notFound().build();
        MediaType mt;
        if (path.endsWith(".xml")) mt = MediaType.APPLICATION_XML;
        else if (path.endsWith(".pdf")) mt = MediaType.APPLICATION_PDF;
        else if (path.endsWith(".xlsx")) mt = MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        else mt = MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.getName() + "\"")
                .body(new FileSystemResource(f));
    }
}
