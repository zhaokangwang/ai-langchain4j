package com.example.ai.report;


import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;

@Service
public class ReportExportService {
    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);
    public byte[] excel(ReportModels.Snapshot report) {
        long started = System.nanoTime();
        log.info("[报表Excel导出开始] reportId={} orderCount={} groupCount={}", report.reportId(), report.rows().size(), report.groups().size());
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            CellStyle header = book.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); header.setFont(font);
            CellStyle money = book.createCellStyle(); money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            Sheet summary = book.createSheet("报表说明与汇总");
            row(summary, 0, "订单报表 · 模拟数据（人民币）");
            row(summary, 1, "报表编号", report.reportId());
            row(summary, 2, "生成时间（北京时间）", Instant.ofEpochMilli(report.createdAt()).atZone(ZoneId.of("Asia/Shanghai")).toString());
            row(summary, 3, "下单日期（含首尾）", report.filter().startDate() + " 至 " + report.filter().endDate());
            row(summary, 4, "权限范围", report.scope());
            row(summary, 5, "状态", report.filter().status().isEmpty() ? "全部" : ReportService.statusLabel(report.filter().status()));
            row(summary, 6, "商品关键词", report.filter().productKeyword());
            row(summary, 7, "统计口径", "每笔订单一行；金额为订单总额，不重复乘数量；全部匹配数据的快照");
            row(summary, 8, "订单数", report.summary().orderCount(), "商品件数", report.summary().quantity());
            row(summary, 9, "订单总金额", report.summary().totalAmount().doubleValue()).getCell(1).setCellStyle(money);
            String dimension = switch (report.filter().groupBy()) { case "STATUS" -> "订单状态"; case "DAY" -> "下单日期"; default -> "商品"; };
            style(row(summary, 11, dimension, "订单数", "商品件数", "订单总金额（元）"), header);
            int i = 12;
            for (var group : report.groups()) row(summary, i++, group.name(), group.orderCount(), group.quantity(), group.totalAmount().doubleValue()).getCell(3).setCellStyle(money);
            summary.setColumnWidth(0, 30 * 256); summary.setColumnWidth(1, 54 * 256); summary.setColumnWidth(2, 20 * 256); summary.setColumnWidth(3, 24 * 256);
            summary.createFreezePane(0, 12);
            Sheet detail = book.createSheet("订单明细");
            style(row(detail, 0, "订单号", "商品", "商品件数", "订单金额（元）", "状态", "下单日期"), header);
            i = 1;
            for (var item : report.rows()) row(detail, i++, item.orderNumber(), item.product(), item.quantity(), item.amount().doubleValue(),
                    ReportService.statusLabel(item.status()), item.orderedOn().toString()).getCell(3).setCellStyle(money);
            for (int col = 0; col < 6; col++) detail.setColumnWidth(col, (col == 1 ? 28 : 22) * 256);
            detail.createFreezePane(0, 1);
            detail.setAutoFilter(new CellRangeAddress(0, Math.max(0, i - 1), 0, 5));
            book.write(output);
            byte[] bytes = output.toByteArray();
            log.info("[报表Excel文件生成完成] reportId={} bytes={} durationMs={}", report.reportId(), bytes.length,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return bytes;
        } catch (IOException | RuntimeException error) {
            log.error("[报表Excel导出失败] reportId={}", report.reportId(), error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("生成 Excel 失败", error);
        }
    }
    private Row row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            // 字符串始终写成文本单元格，不能被当成公式执行。
            if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(String.valueOf(values[i]));
        }
        return row;
    }
    private void style(Row row, CellStyle style) { for (Cell cell : row) cell.setCellStyle(style); }
}
