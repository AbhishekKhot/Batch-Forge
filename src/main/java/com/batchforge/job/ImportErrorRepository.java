package com.batchforge.job;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ImportErrorRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportErrorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(List<ImportError> errors) {
        String sql = """
                INSERT INTO import_errors (job_id, source_row_number, reason)
                VALUES (:jobId, :sourceRowNumber, :reason)
                ON CONFLICT (job_id, source_row_number) DO NOTHING
                """;
        SqlParameterSource[] params = errors.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("jobId", e.jobId())
                        .addValue("sourceRowNumber", e.sourceRowNumber())
                        .addValue("reason", e.reason()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(sql, params);
    }

    public List<ImportError> findByJobIdOrderByRow(UUID jobId) {
        String sql = """
                SELECT source_row_number, reason
                FROM import_errors
                WHERE job_id = :jobId
                ORDER BY source_row_number
                """;
        return jdbc.query(sql, new MapSqlParameterSource("jobId", jobId),
                (rs, rowNum) -> new ImportError(jobId, rs.getLong("source_row_number"), rs.getString("reason")));
    }
}