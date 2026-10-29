package com.example.ai;

import com.example.ai.model.RecordedModelClient;
import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.repository.ExecutionAuditRepository;
import com.example.ai.repository.OrderReportRepository;
import com.example.ai.report.ReportService;
import com.example.ai.service.ChatToolExecutor;
import com.example.ai.service.CustomerChatService;
import com.example.ai.skill.SkillRouter;
import com.example.ai.tool.CustomerTools;
import com.example.ai.tool.ReportTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

/** Manual assembly for isolated restart/account tests; production uses constructor injection. */
final class TestChatServices {
    private TestChatServices() {
    }

    static CustomerChatService create(ChatModel model, CustomerTools tools, ObjectMapper mapper,
                                      ChatHistoryRepository history, JdbcTemplate jdbc) {
        var audit = new ExecutionAuditRepository(jdbc);
        var reports = new ReportService(new OrderReportRepository(jdbc), tools.identity(), mapper);
        var executor = new ChatToolExecutor(tools, new ReportTools(reports), mapper, audit);
        return new CustomerChatService(new RecordedModelClient(model, audit, history), tools, history,
                null, executor, audit, new SkillRouter());
    }
}
