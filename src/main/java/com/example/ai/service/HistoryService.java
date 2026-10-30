package com.example.ai.service;

import com.example.ai.repository.HistoryRepository;
import com.example.ai.security.DemoIdentity;
import com.example.ai.history.HistoryModels.Change;
import com.example.ai.history.HistoryModels.Conversation;
import com.example.ai.history.HistoryModels.ConversationPage;
import com.example.ai.history.HistoryModels.MessagePage;
import com.example.ai.history.HistoryModels.RunDetail;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HistoryService {
    private final HistoryRepository repository;
    private final TransactionTemplate tx;
    private final DemoIdentity identity;

    public HistoryService(HistoryRepository repository, PlatformTransactionManager manager, DemoIdentity identity) {
        this.repository = repository;
        this.tx = new TransactionTemplate(manager);
        this.identity = identity;
    }

    public ConversationPage conversations(String status, int page, int size) {
        identity.requireActive(null);
        if (!Set.of("ACTIVE", "ARCHIVED").contains(status) || page < 0 || page > 100_000 || size < 1 || size > 100) {
            throw bad("分页或状态参数无效");
        }
        var items = repository.conversations(identity.ownerId(), status, size + 1, page * size);
        return new ConversationPage(List.copyOf(items.subList(0, Math.min(items.size(), size))), page, items.size() > size);
    }

    public MessagePage messages(String id, Long before, int size) {
        identity.requireActive(null);
        if (size < 1 || size > 100 || (before != null && before < 1)) {
            throw bad("消息分页参数无效");
        }
        Conversation conversation = requireConversation(id, false);
        var items = repository.messages(id, before == null ? Long.MAX_VALUE : before, size + 1);
        boolean more = items.size() > size;
        var page = new ArrayList<>(items.subList(0, Math.min(size, items.size())));
        Collections.reverse(page);
        return new MessagePage(conversation, List.copyOf(page), more, more ? page.get(0).id() : null);
    }

    public Conversation change(String id, Change request) {
        identity.requireActive(null);
        if (request.title() == null && request.status() == null) {
            throw bad("请提供标题或状态");
        }
        if (request.title() != null && request.title().isBlank()) {
            throw bad("标题不能为空");
        }
        return tx.execute(ignored -> changeLocked(id, request));
    }

    private Conversation changeLocked(String id, Change request) {
        Conversation old = requireConversation(id, true);
        if (request.status() != null && repository.hasRunningRequests(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话仍在处理，暂不能更改状态");
        }
        String title = request.title() == null ? old.title() : request.title().trim();
        String status = request.status() == null ? old.status() : request.status();
        repository.change(id, title, status);
        if (!title.equals(old.title())) {
            repository.event(id, "RENAME_CONVERSATION", title);
        }
        if (!status.equals(old.status())) {
            repository.event(id, status.equals("ARCHIVED") ? "ARCHIVE_CONVERSATION" : "RESTORE_CONVERSATION", status);
        }
        return requireConversation(id, false);
    }

    public RunDetail run(String requestId, Integer attempt) {
        identity.requireActive(null);
        if (attempt != null && attempt < 1) {
            throw bad("尝试次数无效");
        }
        var row = repository.run(requestId, identity.ownerId()).orElseThrow(this::missing);
        int selected = attempt == null ? row.attempts() : attempt;
        if (selected > row.attempts()) {
            throw missing();
        }
        var events = repository.events(requestId);
        Collections.reverse(events);
        return new RunDetail(requestId, row.conversation(), row.status(), row.attempts(), selected,
                row.input(), row.error(), row.duration(), repository.models(requestId, selected),
                repository.tools(requestId, selected), events);
    }

    private Conversation requireConversation(String id, boolean lock) {
        return repository.conversation(id, identity.ownerId(), lock).orElseThrow(this::missing);
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或不可访问");
    }

    private ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
