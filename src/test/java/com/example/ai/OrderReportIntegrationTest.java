package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.report.ReportModels;

import com.example.ai.report.ReportService;
import com.example.ai.security.DemoIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class OrderReportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @MockitoBean ChatModel model;
    ReportModels.Filter filter(String status, String keyword, String group) {
        return new ReportModels.Filter("2026-10-01", "2026-10-31", status, keyword, group);
    }
    ChatResponse call(String name,String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id(UUID.randomUUID().toString())
                .name(name).arguments(arguments).build())).build();
    }
    @Test void shouldQueryDatabaseAndComputeWholeResultRatherThanCurrentPage() throws Exception {
        var page = reports.create(filter("", "", "STATUS"));
        assertThat(page.summary().orderCount()).isEqualTo(3);
        assertThat(page.summary().quantity()).isEqualTo(4);
        assertThat(page.summary().totalAmount()).isEqualByComparingTo("676.00");
        assertThat(page.groups()).filteredOn(g->g.name().equals("已签收")).singleElement()
                .satisfies(g->{assertThat(g.orderCount()).isEqualTo(2);assertThat(g.totalAmount()).isEqualByComparingTo("498.00");});
        mvc.perform(get("/api/reports/orders/{id}",page.reportId()).param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.summary.totalAmount").value(676.00));
        mvc.perform(get("/api/reports/orders/{id}",page.reportId()).param("page","1").param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].orderNumber").value("A1002"))
                .andExpect(jsonPath("$.hasMore").value(false));
        verifyNoInteractions(model);
    }
    @Test void shouldFilterStatusKeywordAndInclusiveDateAndReturnEmptyResults() {
        var page = reports.create(new ReportModels.Filter("2026-10-05","2026-10-05","DELIVERED","键盘","DAY"));
        assertThat(page.rows()).extracting(ReportModels.OrderRow::orderNumber).containsExactly("A1001");
        assertThat(page.groups()).extracting(ReportModels.Group::name).containsExactly("2026-10-05");
        assertThat(reports.create(filter("DELIVERED","键盘","PRODUCT")).summary().orderCount()).isEqualTo(2);
        for(String literal:List.of("不存在","%","_","' OR 1=1 --")) {
            var empty=reports.create(filter("",literal,"PRODUCT"));
            assertThat(empty.rows()).isEmpty();assertThat(empty.summary().totalAmount()).isEqualByComparingTo("0.00");
        }
    }
    @Test void shouldEnforceAllRoleScopesAndSnapshotOwnership() throws Exception {
        var buyer=reports.create(filter("","","PRODUCT"));
        var staff=new ReportService(new com.example.ai.repository.OrderReportRepository(jdbc),new DemoIdentity("service"),mapper);
        var warehouse=new ReportService(new com.example.ai.repository.OrderReportRepository(jdbc),new DemoIdentity("warehouse"),mapper);
        assertThat(staff.create(filter("","","PRODUCT")).rows()).extracting(ReportModels.OrderRow::orderNumber).containsExactly("A1001");
        assertThat(warehouse.create(filter("","","PRODUCT")).rows()).extracting(ReportModels.OrderRow::orderNumber).containsExactly("A1002");
        assertThatThrownBy(()->staff.load(buyer.reportId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(post("/api/reports/orders").header("X-Role","admin").contentType("application/json").content(mapper.writeValueAsString(filter("","","PRODUCT"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.orderCount").value(3));
        for(String path:List.of("/api/reports/orders/not-found","/api/reports/orders/not-found/download")) mvc.perform(get(path)).andExpect(status().isNotFound());
    }
    @Test void shouldRejectInvalidInputBeforeQueryAndReturnUsefulErrors() throws Exception {
        for(var bad:List.of(new ReportModels.Filter("bad","2026-10-31","","","PRODUCT"),
                new ReportModels.Filter("2026-11-01","2026-10-01","","","PRODUCT"),
                new ReportModels.Filter("2020-01-01","2026-10-31","","","PRODUCT"),
                filter("INVALID","","PRODUCT"),filter("","","DROP TABLE"),filter("","x".repeat(101),"PRODUCT"))) {
            mvc.perform(post("/api/reports/orders").contentType("application/json").content(mapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
        var page=reports.create(filter("","","PRODUCT"));
        mvc.perform(get("/api/reports/orders/{id}",page.reportId()).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/orders/{id}",page.reportId()).param("page","2147483647")).andExpect(status().isBadRequest());
    }
    @Test void shouldExportFullSnapshotWithNumericAmountsEvenAfterDatabaseChanges() throws Exception {
        var page=reports.create(filter("","","PRODUCT"));
        var old=jdbc.queryForObject("SELECT amount FROM report_orders WHERE order_number='A1001'",BigDecimal.class);
        try {
            jdbc.update("UPDATE report_orders SET amount=1 WHERE order_number='A1001'");
            byte[] bytes=mvc.perform(get("/api/reports/orders/{id}/download",page.reportId())).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andReturn().getResponse().getContentAsByteArray();
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(book.getNumberOfSheets()).isEqualTo(2);
                var detail=book.getSheet("订单明细");
                assertThat(detail.getLastRowNum()).isEqualTo(3);
                assertThat(detail.getRow(1).getCell(0).getStringCellValue()).isEqualTo("A1001");
                assertThat(detail.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(299);
                assertThat(book.getSheetAt(0).getRow(9).getCell(1).getNumericCellValue()).isEqualTo(676);
                assertThat(detail.getRow(1).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            }
            assertThat(reports.get(page.reportId(),0,2).summary().totalAmount()).isEqualByComparingTo("676.00");
        } finally {jdbc.update("UPDATE report_orders SET amount=? WHERE order_number='A1001'",old);}
    }
    @Test void shouldRefuseOversizedReportsRatherThanSilentlyTruncate() {
        try {
            jdbc.update("INSERT INTO report_orders SELECT 'LIMIT-'||X,'demo-user','限额演示',1,1.00,'DELIVERED',DATE '2026-10-01' FROM SYSTEM_RANGE(1,10001)");
            assertThatThrownBy(()->reports.create(filter("","限额演示","PRODUCT")))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("10000");
        } finally {jdbc.update("DELETE FROM report_orders WHERE order_number LIKE 'LIMIT-%'");}
    }
    @Test void shouldLoadReportSkillExecuteDatabaseToolAndReplayWithoutCreatingAnotherReport() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(call("loadSkill","{\"skillName\":\"order-report\"}"),
                call("queryOrderReport",mapper.writeValueAsString(filter("DELIVERED","","PRODUCT"))),
                ChatResponse.builder().aiMessage(AiMessage.from("演示报表已生成，2笔订单，共498元。")).build());
        String request=UUID.randomUUID().toString();
        String body=mapper.writeValueAsString(Map.of("message","生成2026年10月已签收订单报表并导出Excel","requestId",request));
        mvc.perform(post("/api/chat").contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.executedTools[1]").value("queryOrderReport"));
        String result=jdbc.queryForObject("SELECT result_text FROM tool_calls WHERE request_id=? AND tool_name='queryOrderReport'",String.class,request);
        var data=mapper.readTree(result);String reportId=data.path("reportId").asText();
        assertThat(data.path("summary").path("totalAmount").decimalValue()).isEqualByComparingTo("498.00");
        mvc.perform(get("/api/reports/orders/{id}/download",reportId)).andExpect(status().isOk());
        mvc.perform(post("/api/chat").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(model,times(3)).chat(any(ChatRequest.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND tool_name='queryOrderReport'",Integer.class,request)).isEqualTo(1);
        var cap=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(3)).chat(cap.capture());
        assertThat(cap.getAllValues().get(0).toolSpecifications()).extracting(t->t.name()).containsExactly("loadSkill");
        assertThat(cap.getAllValues().get(1).toolSpecifications()).extracting(t->t.name()).contains("queryOrderReport").doesNotContain("findMyOrders");
    }
    @Test void shouldRejectReportToolOutsideLoadedSkillAndRecordInvalidDateAsFailed() throws Exception {
        String bad=mapper.writeValueAsString(new ReportModels.Filter("bad","2026-10-31","","","PRODUCT"));
        when(model.chat(any(ChatRequest.class))).thenReturn(call("queryOrderReport",bad),call("loadSkill","{\"skillName\":\"order-report\"}"),
                call("queryOrderReport",bad),ChatResponse.builder().aiMessage(AiMessage.from("请提供正确日期")).build());
        String request=UUID.randomUUID().toString();
        mvc.perform(post("/api/chat").contentType("application/json").content(mapper.writeValueAsString(Map.of("message","查询订单报表","requestId",request))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND status='FAILED'",Integer.class,request)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT result_text FROM tool_calls WHERE request_id=? AND status='FAILED'",String.class,request))
                .anyMatch(v->v.contains("不允许执行")).anyMatch(v->v.contains("yyyy-MM-dd"));
    }
    @Test void shouldServeReportPageAndEntry() throws Exception {
        mvc.perform(get("/reports.html")).andExpect(status().isOk()).andExpect(result ->
                assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("下载完整 Excel"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(result ->
                assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("href=\"reports.html\""));
    }
}
