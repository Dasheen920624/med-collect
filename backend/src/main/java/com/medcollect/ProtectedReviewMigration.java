package com.medcollect;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/** Recalls outstanding UPDATE reviews without resetting the evidence of a previous write attempt. */
public class ProtectedReviewMigration implements CustomTaskChange {
    private int recalled;

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        var json = new ObjectMapper();
        try (var select = connection.prepareStatement("""
                SELECT k.id, k.revision, r.plan_json FROM mc_knowledge k JOIN mc_sync_review r ON r.knowledge_id = k.id
                WHERE (k.status = 'PENDING' AND r.draft_revision = k.revision)
                   OR k.status IN ('APPROVED', 'SYNC_FAILED', 'SYNCING')
                """);
             var origin = connection.prepareStatement("SELECT knowledge_id FROM mc_sync_origin WHERE remote_id = ?");
             var draft = connection.prepareStatement("""
                UPDATE mc_knowledge SET status = 'REFERENCE_ONLY', revision = revision + 1, updated_at = ?,
                sync_lease = NULL, sync_token = NULL WHERE id = ? AND revision = ?
                """);
             var review = connection.prepareStatement("""
                UPDATE mc_sync_review SET plan_json = ?, review_id = ?, draft_revision = ?, reviewer = NULL,
                approved_at = NULL WHERE knowledge_id = ?
                """);
             var event = connection.prepareStatement("""
                INSERT INTO mc_event (id, entity_type, entity_id, action, actor, occurred_at, note)
                VALUES (?, 'KNOWLEDGE', ?, 'SYNC_PROTECTED', 'system', ?, ?)
                """);
             var rows = select.executeQuery()) {
            while (rows.next()) {
                ObjectNode plan = (ObjectNode) json.readTree(rows.getCharacterStream("plan_json"));
                if (!"UPDATE".equals(plan.path("operation").asText())) continue;
                String target = plan.path("target").path("id").asText();
                origin.setString(1, target);
                try (var proof = origin.executeQuery()) { if (proof.next()) continue; }
                plan.put("operation", "REFERENCE_ONLY");
                plan.putObject("form");
                plan.put("uploadPdf", false);
                String id = rows.getString("id");
                int revision = rows.getInt("revision");
                long now = System.currentTimeMillis();
                draft.setLong(1, now);
                draft.setString(2, id);
                draft.setInt(3, revision);
                updateOne(draft);
                review.setCharacterStream(1, new StringReader(json.writeValueAsString(plan)));
                review.setString(2, Support.id());
                review.setInt(3, revision + 1);
                review.setString(4, id);
                updateOne(review);
                event.setString(1, Support.id());
                event.setString(2, id);
                event.setLong(3, now);
                event.setString(4, target);
                updateOne(event);
                recalled++;
            }
        } catch (Exception e) {
            throw new CustomChangeException("Could not reclassify outstanding knowledge updates", e);
        }
    }

    private static void updateOne(PreparedStatement statement) throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException("Knowledge review changed during migration");
    }

    @Override public String getConfirmationMessage() { return "Protected " + recalled + " outstanding reviews"; }
    @Override public void setUp() {}
    @Override public void setFileOpener(ResourceAccessor accessor) {}
    @Override public ValidationErrors validate(Database database) { return new ValidationErrors(); }
}
