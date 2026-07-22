package com.batchforge.job;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ContactCsvParser {

    public interface RowHandler {
        void onValidRow(ContactRow row);
        void onInvalidRow(long sourceRowNumber, String reason);
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9().\\-\\s]{7,20}$");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    public void parse(InputStream csv, RowHandler handler) {
        try (Reader reader = new InputStreamReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            long rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                handleRecord(rowNumber, record, handler);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CSV", e);
        }
    }

    private void handleRecord(long rowNumber, CSVRecord record, RowHandler handler) {
        String email;
        String firstName;
        String lastName;
        String phone;
        try {
            email = trimToNull(record.get("email"));
            firstName = trimToNull(record.get("first_name"));
            lastName = trimToNull(record.get("last_name"));
            phone = trimToNull(record.get("phone"));
        } catch (RuntimeException e) {
            handler.onInvalidRow(rowNumber, "malformed row: " + e.getMessage());
            return;
        }

        List<String> problems = new ArrayList<>();
        if (email == null) {
            problems.add("email is required");
        } else if (!EMAIL.matcher(email).matches()) {
            problems.add("email is invalid");
        }
        if (firstName == null) {
            problems.add("first_name is required");
        }
        if (lastName == null) {
            problems.add("last_name is required");
        }
        if (phone != null && !PHONE.matcher(phone).matches()) {
            problems.add("phone is invalid");
        }

        if (problems.isEmpty()) {
            String rowHash = sha256(String.join("|", email, firstName, lastName, phone == null ? "" : phone));
            handler.onValidRow(new ContactRow(rowNumber, email, firstName, lastName, phone, rowHash));
        } else {
            handler.onInvalidRow(rowNumber, String.join("; ", problems));
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}