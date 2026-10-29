package com.example.ai.report;

import com.example.ai.report.ReportModels.Filter;
import com.example.ai.report.ReportModels.Group;
import com.example.ai.report.ReportModels.OrderRow;
import com.example.ai.report.ReportModels.Page;
import com.example.ai.report.ReportModels.Snapshot;
import com.example.ai.report.ReportModels.Summary;

import com.example.ai.security.DemoIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import com.example.ai.repository.OrderReportRepository;
import com.example.ai.repository.OrderReportRepository.OrderScope;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    public static final int MAX_ROWS = 10_000;
    private final OrderReportRepository repository;
    private final DemoIdentity identity;
    private final ObjectMapper mapper;

    public ReportService(OrderReportRepository repository, DemoIdentity identity, ObjectMapper mapper) {
        this.repository = repository;
        this.identity = identity;
        this.mapper = mapper;
    }

    public Page create(Filter input) {
        String reportId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("[报表生成开始] reportId={} account={} role={} filter={}", reportId, identity.ownerId(), identity.current().role(), input);
        try {
            return createReport(input, reportId, started);
        } catch (ResponseStatusException error) {
            log.warn("[报表生成拒绝] reportId={} reason={} durationMs={}", reportId, error.getReason(), elapsed(started));
            throw error;
        } catch (RuntimeException error) {
            log.error("[报表生成失败] reportId={} durationMs={}", reportId, elapsed(started), error);
            throw error;
        }
    }

    private Page createReport(Filter input, String reportId, long started) {
        Filter filter = validate(input);
        OrderScope scope = switch (identity.current().role()) {
            case BUYER -> new OrderScope(identity.ownerId(), null);
            case CUSTOMER_SERVICE -> new OrderScope(null, "A1001");
            case WAREHOUSE -> new OrderScope(null, "A1002");
        };
        log.info("[报表权限过滤] reportId={} account={} scope={}", reportId, identity.ownerId(), identity.current().scope());
        long queryStarted = System.nanoTime();
        var rows = repository.findOrders(filter, scope, MAX_ROWS + 1);
        log.info("[报表数据库查询完成] reportId={} fetchedRows={} limit={} durationMs={}", reportId, rows.size(), MAX_ROWS, elapsed(queryStarted));
        if (rows.size() > MAX_ROWS) throw bad("匹配订单超过 10000 条，请缩小查询范围");
        Map<String, List<OrderRow>> grouped = new TreeMap<>();
        for (var row : rows) {
            String key = switch (filter.groupBy()) {
                case "STATUS" -> statusLabel(row.status());
                case "DAY" -> row.orderedOn().toString();
                default -> row.product();
            };
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        var groups = grouped.entrySet().stream().map(entry -> {
            var sum = summarize(entry.getValue());
            return new Group(entry.getKey(), sum.orderCount(), sum.quantity(), sum.totalAmount());
        }).toList();
        var snapshot = new Snapshot(reportId, System.currentTimeMillis(), filter,
                identity.current().scope(), true, summarize(rows), groups, List.copyOf(rows));
        try {
            repository.save(snapshot.reportId(), identity.ownerId(), mapper.writeValueAsString(snapshot), snapshot.createdAt());
        } catch (JsonProcessingException error) { throw new IllegalStateException("无法保存报表", error); }
        log.info("[报表汇总与快照保存完成] reportId={} orderCount={} quantity={} totalAmount={} groupBy={} groupCount={} durationMs={}",
                reportId, snapshot.summary().orderCount(), snapshot.summary().quantity(), snapshot.summary().totalAmount(),
                filter.groupBy(), groups.size(), elapsed(started));
        return page(snapshot, 0, 10);
    }

    public Snapshot load(String id) {
        String json = repository.load(id, identity.ownerId()).orElseThrow(() -> {
            log.warn("[报表快照不可访问] reportId={} account={}", id, identity.ownerId());
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可访问的报表");
        });
        try {
            var snapshot = mapper.readValue(json, Snapshot.class);
            log.info("[报表快照读取] reportId={} account={} orderCount={}", id, identity.ownerId(), snapshot.rows().size());
            return snapshot;
        }
        catch (JsonProcessingException error) { throw new IllegalStateException("报表数据格式错误", error); }
    }

    public Page get(String id, int page, int size) {
        var result = page(load(id), page, size);
        log.info("[报表分页预览] reportId={} page={} size={} returnedRows={} totalRows={}", id, page, size, result.rows().size(), result.summary().orderCount());
        return result;
    }

    private Page page(Snapshot snapshot, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > MAX_ROWS) throw bad("分页参数无效，size 为 1–100");
        int start = Math.min(page * size, snapshot.rows().size());
        int end = Math.min(start + size, snapshot.rows().size());
        return new Page(snapshot.reportId(), snapshot.createdAt(), snapshot.filter(), snapshot.scope(), true,
                snapshot.summary(), snapshot.groups(), snapshot.rows().subList(start, end), page, size,
                end < snapshot.rows().size(), "/api/reports/orders/" + snapshot.reportId() + "/download",
                "/reports.html?id=" + snapshot.reportId());
    }

    private Filter validate(Filter input) {
        if (input == null) throw bad("请提供报表条件");
        String start = clean(input.startDate()), end = clean(input.endDate());
        try {
            LocalDate from = LocalDate.parse(start), to = LocalDate.parse(end);
            long days = ChronoUnit.DAYS.between(from, to);
            if (days < 0 || days > 365 || to.getYear() > 9998 || from.getYear() < 1900) throw bad("日期范围应为 1–366 天，年份为 1900–9998");
        } catch (java.time.DateTimeException error) { throw bad("开始和结束日期必须为 yyyy-MM-dd"); }
        String status = clean(input.status()).toUpperCase(Locale.ROOT);
        if (!Set.of("", "DELIVERED", "PENDING_SHIPMENT", "SHIPPED", "CANCELLED").contains(status)) throw bad("订单状态无效");
        String keyword = clean(input.productKeyword());
        if (keyword.length() > 100) throw bad("商品关键词不能超过 100 字");
        String group = clean(input.groupBy()).toUpperCase(Locale.ROOT);
        if (group.isEmpty()) group = "PRODUCT";
        if (!Set.of("PRODUCT", "STATUS", "DAY").contains(group)) throw bad("分组维度仅支持 PRODUCT、STATUS、DAY");
        return new Filter(start, end, status, keyword, group);
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static Summary summarize(List<OrderRow> rows) {
        return new Summary(rows.size(), rows.stream().mapToInt(OrderRow::quantity).sum(),
                rows.stream().map(OrderRow::amount).reduce(new BigDecimal("0.00"), BigDecimal::add));
    }
    public static String statusLabel(String status) {
        return switch (status) { case "DELIVERED" -> "已签收"; case "PENDING_SHIPMENT" -> "待发货";
            case "SHIPPED" -> "已发货"; case "CANCELLED" -> "已取消"; default -> status; };
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
