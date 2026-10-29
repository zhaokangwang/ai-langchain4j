package com.example.ai.repository;

import com.example.ai.report.ReportModels.Filter;
import com.example.ai.report.ReportModels.OrderRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderReportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public OrderReportRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Constructed from the server identity, never bound from HTTP or model arguments. */
    public record OrderScope(String ownerId, String orderNumber) {}

    public List<OrderRow> findOrders(Filter filter, OrderScope scope, int limit) {
        var params = new MapSqlParameterSource().addValue("start", LocalDate.parse(filter.startDate()))
                .addValue("end", LocalDate.parse(filter.endDate()).plusDays(1)).addValue("limit", limit);
        String scopeSql;
        if (scope.ownerId() != null) {
            scopeSql = "owner_id=:owner";
            params.addValue("owner", scope.ownerId());
        } else {
            scopeSql = "order_number=:order";
            params.addValue("order", scope.orderNumber());
        }
        String sql = """
                SELECT order_number,product,quantity,amount,status,ordered_on FROM report_orders
                WHERE
                """ + scopeSql + " AND ordered_on>=:start AND ordered_on<:end";
        if (!filter.status().isEmpty()) {
            sql += " AND status=:status";
            params.addValue("status", filter.status());
        }
        if (!filter.productKeyword().isEmpty()) {
            sql += " AND product LIKE :keyword ESCAPE '!'";
            params.addValue("keyword", "%" + filter.productKeyword().replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%");
        }
        return jdbc.query(sql + " ORDER BY ordered_on,order_number LIMIT :limit", params,
                (rs, n) -> new OrderRow(rs.getString("order_number"), rs.getString("product"), rs.getInt("quantity"),
                        rs.getBigDecimal("amount"), rs.getString("status"), rs.getDate("ordered_on").toLocalDate()));
    }

    public void save(String id, String owner, String json, long createdAt) {
        jdbc.update("INSERT INTO order_report_snapshots(id,owner_id,snapshot_json,created_at) VALUES(:id,:owner,:json,:time)",
                new MapSqlParameterSource().addValue("id", id).addValue("owner", owner)
                        .addValue("json", json).addValue("time", createdAt));
    }

    public Optional<String> load(String id, String owner) {
        return jdbc.queryForList("SELECT snapshot_json FROM order_report_snapshots WHERE id=:id AND owner_id=:owner",
                new MapSqlParameterSource().addValue("id", id).addValue("owner", owner), String.class)
                .stream().findFirst();
    }
}
