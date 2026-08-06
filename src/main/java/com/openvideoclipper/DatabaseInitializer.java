package com.openvideoclipper;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import static com.openvideoclipper.utils.LogUtil.info;
import static com.openvideoclipper.utils.LogUtil.error;
import com.openvideoclipper.config.OvcConfig;
import jakarta.inject.Inject;
import javax.sql.DataSource;

@ApplicationScoped
public class DatabaseInitializer {

    @Inject
    OvcConfig config;

    @Inject
    DataSource dataSource;

    void onStart(@Observes StartupEvent ev) {
        try {
            // Ensure the storage directory exists before Hibernate attempts to connect to the DB
            Path storagePath = config.getStoragePath();
            if (storagePath != null) {
                Files.createDirectories(storagePath);
            }
        } catch (IOException e) {
            error("Critical Error: Failed to create storage directory during startup: " + e.getMessage(), e);
        }
        // Run schema migration for existing databases where CHECK constraint
        // was generated before TRANSCRIPTION_REVIEW was added to the JobStatus enum
        migrateVideoJobsCheckConstraint();
        createSceneBoundariesTable();
    }

    /**
     * Ensures the scene_boundaries table exists for databases created before the
     * SceneBoundary entity was introduced. Hibernate's database.generation=update
     * creates it for new databases; this only adds the table and never alters
     * existing tables or columns.
     */
    private void createSceneBoundariesTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS scene_boundaries (" +
                "id uuid NOT NULL, " +
                "job_id uuid NOT NULL, " +
                "start_time_seconds double precision, " +
                "end_time_seconds double precision, " +
                "scene_index integer, " +
                "PRIMARY KEY (id))");
            info("[DB Migration] scene_boundaries table ensured");
        } catch (Exception e) {
            error("[DB Migration] Failed to ensure scene_boundaries table: " + e.getMessage());
        }
    }

    /**
     * Ensures the video_jobs.status CHECK constraint includes TRANSCRIPTION_REVIEW.
     * Hibernate's database.generation=update does NOT modify existing CHECK constraints,
     * so we need a manual migration for databases created before TRANSCRIPTION_REVIEW existed.
     */
    private void migrateVideoJobsCheckConstraint() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='video_jobs'"
            );
            if (rs.next()) {
                String createSql = rs.getString("sql");
                if (createSql != null && !createSql.contains("TRANSCRIPTION_REVIEW")) {
                    info("[DB Migration] Adding TRANSCRIPTION_REVIEW to video_jobs CHECK constraint...");
                    int parenStart = createSql.indexOf('(');
                    int parenEnd = createSql.lastIndexOf(')');
                    if (parenStart < 0 || parenEnd < 0 || parenEnd <= parenStart) {
                        error("[DB Migration] Could not parse video_jobs schema, skipping");
                        rs.close();
                        return;
                    }
                    String columns = createSql.substring(parenStart, parenEnd + 1);
                    String updatedColumns = columns.replace(
                        "'UPLOADED','TRANSCRIBING','ANALYZING'",
                        "'UPLOADED','TRANSCRIBING','TRANSCRIPTION_REVIEW','ANALYZING'"
                    );
                    String newTableSql = "CREATE TABLE video_jobs_new " + updatedColumns;

                    // Sequential DDL: SQLite may auto-commit each DDL, so run bare without tx wrapping
                    try (Statement migStmt = conn.createStatement()) {
                        migStmt.execute("PRAGMA foreign_keys=OFF");
                        migStmt.execute(newTableSql);
                        migStmt.execute("INSERT INTO video_jobs_new SELECT * FROM video_jobs");
                        migStmt.execute("DROP TABLE video_jobs");
                        migStmt.execute("ALTER TABLE video_jobs_new RENAME TO video_jobs");
                        info("[DB Migration] Successfully added TRANSCRIPTION_REVIEW to CHECK constraint");
                    } catch (Exception e) {
                        error("[DB Migration] Failed to migrate video_jobs table: " + e.getMessage());
                    } finally {
                        try (Statement fkStmt = conn.createStatement()) {
                            fkStmt.execute("PRAGMA foreign_keys=ON");
                        } catch (Exception ignored) {}
                    }
                } else if (createSql != null) {
                    info("[DB Migration] CHECK constraint already has TRANSCRIPTION_REVIEW, skipping");
                }
            }
            rs.close();
        } catch (Exception e) {
            error("[DB Migration] Error checking/migrating video_jobs table: " + e.getMessage());
        }
    }
}
