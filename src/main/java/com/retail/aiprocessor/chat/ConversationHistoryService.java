package com.retail.aiprocessor.chat;

import com.retail.aiprocessor.model.ChatTurn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationHistoryService {
    private static final int CONTEXT_MESSAGE_LIMIT = 12;
    private final JdbcTemplate jdbc;

    public ConversationHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID open(UUID userId, String conversationId, String firstQuestion) {
        if (conversationId == null || conversationId.isBlank()) {
            UUID id = UUID.randomUUID();
            String title = firstQuestion.length() <= 200 ? firstQuestion : firstQuestion.substring(0, 197) + "...";
            jdbc.update("insert into ai_processor.ai_conversations(id,user_id,title) values (?,?,?)", id, userId, title);
            return id;
        }
        UUID id;
        try { id = UUID.fromString(conversationId); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversationId");
        }
        if (jdbc.query("select id from ai_processor.ai_conversations where id=? and user_id=?", (rs, row) -> rs.getObject(1), id, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return id;
    }

    public List<ChatTurn> context(UUID conversationId) {
        return jdbc.query("select role,content,thought_signature from (select id,role,content,thought_signature,created_at from ai_processor.ai_messages " +
                        "where conversation_id=? order by created_at desc,id desc limit ?) recent " +
                        "order by created_at,id", (rs, row) -> new ChatTurn(rs.getString("role"), rs.getString("content"),
                        rs.getString("thought_signature")),
                conversationId, CONTEXT_MESSAGE_LIMIT);
    }

    @Transactional
    public void append(UUID conversationId, String question, String answer, String thoughtSignature, List<String> sources) {
        jdbc.update("insert into ai_processor.ai_messages(conversation_id,role,content,data_sources) values (?,?,?,?)",
                conversationId, "user", question, "");
        jdbc.update("insert into ai_processor.ai_messages(conversation_id,role,content,data_sources,thought_signature) values (?,?,?,?,?)",
                conversationId, "assistant", answer, String.join(",", sources), thoughtSignature);
        jdbc.update("update ai_processor.ai_conversations set updated_at=? where id=?", Timestamp.from(Instant.now()), conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationPage list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversation page");
        }
        long totalElements = jdbc.queryForObject("select count(*) from ai_processor.ai_conversations where user_id=?",
                Long.class, userId);
        List<ConversationSummary> content = jdbc.query("select id,title,updated_at from ai_processor.ai_conversations " +
                        "where user_id=? order by updated_at desc,id desc limit ? offset ?",
                (rs, row) -> new ConversationSummary(rs.getObject("id", UUID.class).toString(), rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant().toString()), userId, size, (long) page * size);
        long totalPages = totalElements / size + (totalElements % size == 0 ? 0 : 1);
        boolean hasPrevious = page > 0;
        boolean hasMore = (long) page + 1 < totalPages;
        return new ConversationPage(content, page, size, totalElements, totalPages, hasPrevious, hasMore, hasMore);
    }

    public ConversationDetail get(UUID userId, String conversationId) {
        UUID id;
        try { id = UUID.fromString(conversationId); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversationId"); }
        var summaries = jdbc.query("select id,title,updated_at from ai_processor.ai_conversations where id=? and user_id=?",
                (rs, row) -> new ConversationSummary(rs.getObject("id", UUID.class).toString(), rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant().toString()), id, userId);
        if (summaries.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        List<StoredMessage> messages = jdbc.query("select role,content,data_sources,created_at from ai_processor.ai_messages " +
                        "where conversation_id=? order by created_at,id", (rs, row) -> new StoredMessage(rs.getString("role"),
                        rs.getString("content"), rs.getString("data_sources").isBlank() ? List.of() :
                        List.of(rs.getString("data_sources").split(",")), rs.getTimestamp("created_at").toInstant().toString()), id);
        return new ConversationDetail(summaries.get(0), messages);
    }

    public record ConversationSummary(String conversationId, String title, String updatedAt) { }
    public record ConversationPage(List<ConversationSummary> content, int page, int size, long totalElements,
                                   long totalPages, boolean hasPrevious, boolean hasNext, boolean hasMore) { }
    public record StoredMessage(String role, String content, List<String> dataSources, String createdAt) { }
    public record ConversationDetail(ConversationSummary conversation, List<StoredMessage> messages) { }
}
