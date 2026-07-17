package com.batchforge.job;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ImportedRecordRepository {

    private static final String INSERT_SQL = """
            INSERT INTO imported_records
                (job_id, source_row_number, email, first_name, last_name, phone, row_hash)
            VALUES
                (:jobId, :sourceRowNumber, :email, :firstName, :lastName, :phone, :rowHash)
            ON CONFLICT (job_id, source_row_number) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ImportedRecordRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Batch-insert rows; a row already present for (job, source row) is silently skipped — the
     *  idempotency guarantee that makes a redelivered/resumed job safe. */
    public void insertBatch(List<ImportedRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = records.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("jobId", r.jobId())
                        .addValue("sourceRowNumber", r.sourceRowNumber())
                        .addValue("email", r.email())
                        .addValue("firstName", r.firstName())
                        .addValue("lastName", r.lastName())
                        .addValue("phone", r.phone())
                        .addValue("rowHash", r.rowHash()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_SQL, batch);
    }

    public long countByJobId(UUID jobId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM imported_records WHERE job_id = :jobId",
                new MapSqlParameterSource("jobId", jobId), Long.class);
        return count == null ? 0L : count;
    }
}