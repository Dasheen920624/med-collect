package com.medcollect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.Map;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyOriginalMigrationTest {
    @TempDir Path originals;

    @Test
    void verifiesFilesBeforeRemovingLegacyColumnsAndMigratesRowsAddedAfterPreview() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:legacy-originals;MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        migrate(dataSource, "001-collection.sql", "file-storage-verify");
        var jdbc = new JdbcTemplate(dataSource);
        var db = JdbcClient.create(jdbc);
        byte[] pdf = "%PDF-1.4\nlegacy test fixture\n".getBytes(StandardCharsets.UTF_8);
        String pdfId = seed(jdbc, "PDF", pdf, null);

        migrate(dataSource, "changelog.xml", "file-storage-verify");
        Path pdfPath = path(db, pdfId);
        assertThat(Files.readAllBytes(pdfPath)).isEqualTo(pdf);
        assertThat(db.sql("SELECT original_bytes FROM mc_original WHERE material_id = :id")
                .param("id", pdfId).query((rs, row) -> rs.getBytes(1)).single()).isEqualTo(pdf);

        Files.writeString(pdfPath, "corrupted after the initial copy");
        assertThatThrownBy(() -> migrate(dataSource, "changelog.xml", "file-storage-cutover"))
                .isInstanceOf(LiquibaseException.class).hasStackTraceContaining("Stored files do not match material");
        assertThat(db.sql("SELECT original_bytes FROM mc_original WHERE material_id = :id")
                .param("id", pdfId).query((rs, row) -> rs.getBytes(1)).single()).isEqualTo(pdf);

        Files.write(pdfPath, pdf);
        String extracted = "题名\t测试数据\n文献\t一行\n";
        byte[] csv = "题名,测试数据\n文献,一行\n".getBytes(StandardCharsets.UTF_8);
        String csvId = seed(jdbc, "CSV", csv, extracted);
        migrate(dataSource, "changelog.xml", "file-storage-cutover");
        migrate(dataSource, "changelog.xml", "file-storage-cutover");

        Path csvPath = path(db, csvId);
        assertThat(Files.readAllBytes(pdfPath)).isEqualTo(pdf);
        assertThat(Files.readAllBytes(csvPath)).isEqualTo(csv);
        assertThat(Files.readString(csvPath.resolveSibling("extracted.txt"))).isEqualTo(extracted);
        assertThat(db.sql("SELECT source_abstract FROM mc_material WHERE id = :id")
                .param("id", csvId).query(String.class).single()).isEqualTo("Retained abstract");
        assertThat(db.sql("""
                SELECT count(*) FROM information_schema.columns WHERE
                   (table_name = 'MC_ORIGINAL' AND column_name = 'ORIGINAL_BYTES') OR
                   (table_name = 'MC_MATERIAL' AND column_name = 'RAW_TEXT')
                """).query(Long.class).single()).isZero();
    }

    private void migrate(DriverManagerDataSource dataSource, String changelog, String context) throws LiquibaseException {
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/" + changelog);
        liquibase.setContexts(context);
        liquibase.setChangeLogParameters(Map.of("originalDir", originals.toString()));
        liquibase.afterPropertiesSet();
    }

    private String seed(JdbcTemplate jdbc, String format, byte[] bytes, String text) {
        String id = Support.id();
        jdbc.update("""
                INSERT INTO mc_material (id, fingerprint, sha256, title, format_code, file_name, mime_type,
                                         size_bytes, source_abstract, raw_text, status, captured_at, revision)
                VALUES (?, ?, ?, 'Legacy fixture', ?, 'legacy-fixture', 'application/octet-stream',
                        ?, 'Retained abstract', ?, 'APPROVED', 0, 1)
                """, id, Support.sha(id), Support.sha(bytes), format, bytes.length, text);
        jdbc.update("INSERT INTO mc_original (material_id, original_bytes) VALUES (?, ?)",
                new Object[] {id, bytes}, new int[] {Types.VARCHAR, Types.BLOB});
        return id;
    }

    private Path path(JdbcClient db, String id) {
        return originals.resolve(db.sql("SELECT original_path FROM mc_original WHERE material_id = :id")
                .param("id", id).query(String.class).single());
    }
}
