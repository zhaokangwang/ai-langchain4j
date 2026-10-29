package com.example.ai.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReportModels {
    private ReportModels() {
    }

    public record Filter(String startDate, String endDate, String status, String productKeyword, String groupBy) {}
    public record OrderRow(String orderNumber, String product, int quantity, BigDecimal amount, String status, LocalDate orderedOn) {}
    public record Summary(int orderCount, int quantity, BigDecimal totalAmount) {}
    public record Group(String name, int orderCount, int quantity, BigDecimal totalAmount) {}
    public record Snapshot(String reportId, long createdAt, Filter filter, String scope, boolean simulated,
                           Summary summary, List<Group> groups, List<OrderRow> rows) {}
    public record Page(String reportId, long createdAt, Filter filter, String scope, boolean simulated,
                       Summary summary, List<Group> groups, List<OrderRow> rows, int page, int size,
                       boolean hasMore, String downloadUrl, String previewUrl) {}
}
