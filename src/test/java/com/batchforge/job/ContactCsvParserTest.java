package com.batchforge.job;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContactCsvParserTest {

    private final ContactCsvParser parser = new ContactCsvParser();

    @Test
    void separatesValidRowsFromInvalidWithReasons() {
        String csv = """
                email,first_name,last_name,phone
                alice@example.com,Alice,Smith,+1 555 123 4567
                ,Bob,Jones,555-0000
                carol@example.com,,Doe,
                not-an-email,Dan,Ray,
                erin@example.com,Erin,Fox,
                """;

        List<ContactRow> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), new ContactCsvParser.RowHandler() {
            @Override
            public void onValidRow(ContactRow row) {
                valid.add(row);
            }

            @Override
            public void onInvalidRow(long sourceRowNumber, String reason) {
                invalid.add(sourceRowNumber + ": " + reason);
            }
        });

        assertThat(valid).extracting(ContactRow::sourceRowNumber).containsExactly(1L, 5L);
        assertThat(valid).extracting(ContactRow::email).containsExactly("alice@example.com", "erin@example.com");
        assertThat(valid).allSatisfy(r -> assertThat(r.rowHash()).hasSize(64));
        assertThat(invalid).hasSize(3);
        assertThat(invalid.get(0)).startsWith("2:").contains("email is required");
        assertThat(invalid.get(1)).startsWith("3:").contains("first_name is required");
        assertThat(invalid.get(2)).startsWith("4:").contains("email is invalid");
    }

    @Test
    void emptyOptionalPhoneBecomesNull() {
        String csv = """
                email,first_name,last_name,phone
                zoe@example.com,Zoe,Lee,
                """;

        List<ContactRow> valid = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), new ContactCsvParser.RowHandler() {
            @Override
            public void onValidRow(ContactRow row) {
                valid.add(row);
            }

            @Override
            public void onInvalidRow(long sourceRowNumber, String reason) {
            }
        });

        assertThat(valid).hasSize(1);
        assertThat(valid.get(0).phone()).isNull();
    }
}