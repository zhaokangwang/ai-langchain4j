package com.example.ai.controller;

import com.example.ai.report.ReportModels;

import com.example.ai.report.ReportService;
import com.example.ai.report.ReportExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/reports/orders")
@Tag(name = "订单报表", description = "模拟订单、权限过滤、结果快照及 Excel 导出")
public class ReportController {
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private final ReportService reports;
    private final ReportExportService export;
    public ReportController(ReportService reports, ReportExportService export) { this.reports = reports; this.export = export; }
    @PostMapping
    @Operation(summary = "按条件查询并保存报表快照，日期含首尾")
    public ReportModels.Page create(@RequestBody ReportModels.Filter filter) {
        log.info("[报表HTTP入口] POST /api/reports/orders -> ReportService.create，直接查询数据库，不调用模型");
        return reports.create(filter);
    }
    @GetMapping("/{id}")
    @Operation(summary = "分页读取已生成报表，page 从 0 开始")
    public ResponseEntity<ReportModels.Page> get(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(id, page, size));
    }
    @GetMapping("/{id}/download")
    @Operation(summary = "下载同一快照的完整 Excel，不受预览分页影响")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var report = reports.load(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("orders-" + report.reportId() + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(export.excel(report));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String,String>> inputError(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("message", error.getReason() == null ? "报表请求失败" : error.getReason()));
    }
}
