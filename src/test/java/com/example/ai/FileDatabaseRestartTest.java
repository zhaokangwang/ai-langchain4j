package com.example.ai;

import com.example.ai.tool.CustomerTools;
import com.example.ai.skill.SkillRouter;
import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.CustomerChatService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真正关闭并重新打开隔离的 H2 文件，验证持久化而非共享内存。 */
class FileDatabaseRestartTest {
    @TempDir Path directory;

    private HikariDataSource open() {
        return open(false);
    }

    private HikariDataSource open(boolean versionOneOnly) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        var migration = Flyway.configure().dataSource(dataSource);
        if (versionOneOnly) migration.target(org.flywaydb.core.api.MigrationVersion.fromVersion("1"));
        migration.load().migrate();
        return dataSource;
    }

    private CustomerChatService service(HikariDataSource ds, ChatModel model) throws Exception {
        var jdbc = new JdbcTemplate(ds);
        var mapper = new ObjectMapper();
        var history = new ChatHistoryRepository(jdbc, new DataSourceTransactionManager(ds), mapper, new com.example.ai.security.DemoIdentity("buyer"));
        history.recoverInterrupted();
        return TestChatServices.create(model, new CustomerTools(new com.example.ai.skill.SkillRegistry(mapper), new com.example.ai.security.DemoIdentity("buyer")), mapper, history, jdbc);
    }

    private ChatResponse call(String id, String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build())).build();
    }

    @Test
    void shouldUpgradeLegacyFileWithoutDroppingHistory() throws Exception {
        String conversation;
        try(var ds=open(true)) {
            var history=new ChatHistoryRepository(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),new ObjectMapper(), new com.example.ai.security.DemoIdentity("buyer"));
            var run=history.begin("legacy-request",null,"旧问题","CHAT");
            conversation=run.conversationId();
            history.complete(run,new com.example.ai.chat.ChatResponse("旧回答",conversation,java.util.List.of(),run.requestId()),java.util.List.of(UserMessage.from("旧问题"),AiMessage.from("旧回答")));
        }
        try(var ds=open()) {
            var history=new ChatHistoryRepository(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),new ObjectMapper(), new com.example.ai.security.DemoIdentity("buyer"));
            assertThat(history.savedResponse(history.find("legacy-request").orElseThrow()).answer()).isEqualTo("旧回答");
            assertThat(history.businessState(conversation)).isEqualTo(SkillRouter.State.empty());
            assertThat(new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='CHAT_RUNS' AND COLUMN_NAME='BUSINESS_STATE_JSON'",Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void shouldRestoreFullToolProtocolAndCachedResponseAfterClosingFileDatabase() throws Exception {
        String conversation;
        ChatModel firstModel = mock(ChatModel.class);
        when(firstModel.chat(any(ChatRequest.class))).thenReturn(
                call("skill-call", "loadSkill", "{\"skillName\":\"return-policy-guide\"}"),
                call("search-call", "findMyOrders", "{\"productKeyword\":\"键盘\"}"),
                ChatResponse.builder().aiMessage(AiMessage.from("请选择机械键盘还是蓝牙键盘")).build());
        try (var ds = open()) {
            var result = service(ds, firstModel).chat(null, "键盘坏了想退货", "first-request");
            conversation = result.conversationId();
            assertThat(result.executedTools()).containsExactly("loadSkill", "findMyOrders");
        }
        assertThat(directory.resolve("history.mv.db")).exists();

        ChatModel restartedModel = mock(ChatModel.class);
        when(restartedModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("已根据历史确定机械键盘，请补充故障视频")).build());
        try (var ds = open()) {
            var service = service(ds, restartedModel);
            var cached = service.chat(null, "键盘坏了想退货", "first-request");
            assertThat(cached.conversationId()).isEqualTo(conversation);
            verifyNoInteractions(restartedModel);
            service.chat(conversation, "机械键盘那个", "second-request");
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(restartedModel).chat(captor.capture());
            var restored = captor.getValue().messages();
            assertThat(((SystemMessage)restored.get(0)).text()).contains("R3", "退货条件", "A1001");
            var fullHistory=new ChatHistoryRepository(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),new ObjectMapper(), new com.example.ai.security.DemoIdentity("buyer")).recentContext(conversation);
            assertThat(fullHistory).anyMatch(m->m instanceof ToolExecutionResultMessage t && t.id().equals("skill-call"));
            assertThat(restored).noneMatch(m->m instanceof ToolExecutionResultMessage);
            assertThat(restored.toString()).contains("键盘坏了想退货", "请选择机械键盘还是蓝牙键盘", "机械键盘那个");
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='IDX_CONVERSATIONS_LIST'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_runs", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_calls", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE LENGTH(skill_sha256)=64", Integer.class)).isEqualTo(1);
        }
    }
}
