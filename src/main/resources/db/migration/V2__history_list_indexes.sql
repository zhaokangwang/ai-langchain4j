CREATE INDEX idx_conversations_list ON conversations(owner_id, source, status, updated_at, id);
CREATE INDEX idx_messages_run_id ON chat_messages(request_id, id);
