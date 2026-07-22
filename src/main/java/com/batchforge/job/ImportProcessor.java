package com.batchforge.job;

import com.batchforge.storage.MinioStorageService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
        writeErrorReport(jobId, ctx.sourceObjectKey());
    }

    private void writeErrorReport(UUID jobId, String sourceObjectKey) {
        List<ImportError> errors = jobProcessing.getErrors(jobId);
        if (errors.isEmpty()) {
            return;
        }
        String errorKey = errorReportKey(sourceObjectKey);
        storage.putObject(errorKey, renderCsv(errors), "text/csv");
        jobProcessing.attachErrorReport(jobId, errorKey);
    }

    private byte[] renderCsv(List<ImportError> errors) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord("row_number", "error");
            for (ImportError error : errors) {
                printer.printRecord(error.sourceRowNumber(), error.reason());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render error report", e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String errorReportKey(String sourceObjectKey) {
        return sourceObjectKey.substring(0, sourceObjectKey.lastIndexOf('/') + 1) + "errors.csv";
    }

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