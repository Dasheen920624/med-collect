package com.medcollect;

import java.io.IOException;
import java.sql.SQLException;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/** Copies legacy originals and extracted text through Liquibase's JDBC transaction. */
public class LegacyOriginalMigration implements CustomTaskChange {
    private String originalDir;
    private int verified;

    public void setOriginalDir(String originalDir) { this.originalDir = originalDir; }

    @Override
    public void execute(Database database) throws CustomChangeException {
        var files = new OriginalFiles(originalDir);
        var connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        String materialId = null;
        verified = 0;
        try (var query = connection.prepareStatement("""
                SELECT m.id, m.format_code, m.size_bytes, m.sha256, m.raw_text,
                       o.material_id, o.original_bytes, o.original_path
                FROM mc_material m LEFT JOIN mc_original o ON o.material_id = m.id
                ORDER BY m.id
                """); var update = connection.prepareStatement("""
                UPDATE mc_original SET original_path = ? WHERE material_id = ? AND original_path IS NULL
                """)) {
            files.prepare();
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    materialId = rows.getString("id");
                    if (rows.getString("material_id") == null) throw new IOException("Missing original record");
                    String path = rows.getString("original_path");
                    byte[] legacy = rows.getBytes("original_bytes");
                    String expectedSha = rows.getString("sha256");
                    long expectedSize = rows.getLong("size_bytes");
                    if (legacy != null) {
                        verify(legacy, expectedSize, expectedSha);
                        String copied = files.write(materialId, rows.getString("format_code"), legacy,
                                rows.getString("raw_text"));
                        if (path == null) {
                            update.setString(1, copied);
                            update.setString(2, materialId);
                            if (update.executeUpdate() != 1) throw new SQLException("Original path changed during migration");
                            path = copied;
                        } else if (!path.equals(copied)) {
                            throw new IOException("Existing original path differs from the migrated path");
                        }
                    }
                    verify(files.read(path), expectedSize, expectedSha);
                    verified++;
                }
            }
        } catch (SQLException | IOException e) {
            throw new CustomChangeException("Could not migrate original for material " + materialId, e);
        }
    }

    private static void verify(byte[] bytes, long expectedSize, String expectedSha) throws IOException {
        if (bytes.length != expectedSize || bytes.length == 0 || bytes.length > DocumentReader.MAX_BYTES
                || !Support.sha(bytes).equalsIgnoreCase(expectedSha)) {
            throw new IOException("Original size or SHA-256 differs from the material record");
        }
    }

    @Override public String getConfirmationMessage() { return "Verified " + verified + " stored originals"; }
    @Override public void setUp() {}
    @Override public void setFileOpener(ResourceAccessor accessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        var errors = new ValidationErrors();
        if (originalDir == null || originalDir.isBlank() || "${originalDir}".equals(originalDir)) {
            errors.addError("Original storage directory is required");
        }
        return errors;
    }
}
