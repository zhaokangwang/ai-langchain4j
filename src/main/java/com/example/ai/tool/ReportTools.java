package com.example.ai.tool;

import com.example.ai.report.ReportModels;

import com.example.ai.report.ReportService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReportTools {
    private static final Logger log = LoggerFactory.getLogger(ReportTools.class);
    private final ReportService reports;
    public ReportTools(ReportService reports) { this.reports = reports; }

    @Tool("查询当前身份可访问的数据库演示订单，保存报表快照并提供 Excel 下载。日期按下单日期，首尾均包含；统计全部匹配数据，预览仅前10条。不得传入SQL或身份。")
    public ReportModels.Page queryOrderReport(
            @P("开始日期，yyyy-MM-dd") String startDate,
            @P("结束日期，yyyy-MM-dd，包含当天") String endDate,
            @P("状态：DELIVERED已签收、PENDING_SHIPMENT待发货、SHIPPED已发货、CANCELLED已取消，全部传空字符串") String status,
            @P("商品关键词，不限传空字符串") String productKeyword,
            @P("汇总维度：PRODUCT按商品、STATUS按状态、DAY按下单日期") String groupBy) {
        log.info("[报表工具调用] queryOrderReport -> ReportService.create，使用模型提取的筛选条件查询数据库");
        var result = reports.create(new ReportModels.Filter(startDate, endDate, status, productKeyword, groupBy));
        log.info("[报表工具返回模型] reportId={} totalRows={} previewRows={} totalAmount={} previewUrl={}", result.reportId(),
                result.summary().orderCount(), result.rows().size(), result.summary().totalAmount(), result.previewUrl());
        return result;
    }
}
