package com.batchforge.job;

import com.batchforge.storage.MinioStorageService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ImportProcessor {

    private static final int BATCH_SIZE = 500;

    private final JobProcessingService jobProcessing;
    private final MinioStorageService storage;
    private final ContactCsvParser parser;

    public ImportProcessor(JobProcessingService jobProcessing, MinioStorageService storage, ContactCsvParser parser) {
        this.jobProcessing = jobProcessing;
        this.storage = storage;
        this.parser = parser;
    }

    public void process(UUID jobId) {
        JobProcessingService.ProcessingContext ctx = jobProcessing.loadProcessingContext(jobId);
        try (InputStream in = storage.getObject(ctx.sourceObjectKey())) {
            Accumulator accumulator = new Accumulator(jobId, ctx.resumeFrom());
            parser.parse(in, accumulator);
            accumulator.flushRemaining();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed while reading source for job " + jobId, e);
        }
    }

    /** Buffers imported rows and failures into ~BATCH_SIZE chunks, checkpointing each flush
     *  atomically, and skips anything at or below the resume point on a redelivery. */
    private final class Accumulator implements ContactCsvParser.RowHandler {

        private final UUID jobId;
        private final long resumeFrom;
        private final List<ImportedRecord> imported = new ArrayList<>(BATCH_SIZE);
        private final List<ImportError> failed = new ArrayList<>();
        private long lastRow;

        private Accumulator(UUID jobId, long resumeFrom) {
            this.jobId = jobId;
            this.resumeFrom = resumeFrom;
            this.lastRow = resumeFrom;
        }

        @Override
        public void onValidRow(ContactRow row) {
            if (row.sourceRowNumber() <= resumeFrom) {
                return;
            }
            imported.add(new ImportedRecord(jobId, row.sourceRowNumber(),
                    row.email(), row.firstName(), row.lastName(), row.phone(), row.rowHash()));
            lastRow = row.sourceRowNumber();
            maybeFlush();
        }

        @Override
        public void onInvalidRow(long sourceRowNumber, String reason) {
            if (sourceRowNumber <= resumeFrom) {
                return;
            }
            failed.add(new ImportError(jobId, sourceRowNumber, reason));
            lastRow = sourceRowNumber;
            maybeFlush();
        }

        private void maybeFlush() {
            if (imported.size() >= BATCH_SIZE || failed.size() >= BATCH_SIZE) {
                flush();
            }
        }

        private void flush() {
            jobProcessing.flushBatch(jobId, List.copyOf(imported), List.copyOf(failed),
                    lastRow, imported.size(), failed.size());
            imported.clear();
            failed.clear();
        }

        void flushRemaining() {
            if (!imported.isEmpty() || !failed.isEmpty()) {
                flush();
            }
        }
    }
}