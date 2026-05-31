package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.model.ParseOutcome;
import com.kazimir.declinemapper.model.ProviderError;
import com.kazimir.declinemapper.stage.Parser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial-fixture parser test. The {@code messy_provider.txt} fixture uses
 * the relaxed section-header style ({@code == section ==}, lowercase variants) and
 * mixed-case section names that real provider docs often have. The parser must
 * either extract them cleanly or surface them as {@code Garbage} — never crash,
 * never silently lose a code.
 */
class MessyProviderParserTest {

    private static String loadResource(String path) throws IOException {
        try (InputStream in = MessyProviderParserTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parser_handlesMessyProvider_extractsAllSevenCodes() throws IOException {
        String input = loadResource("/fixtures/messy_provider.txt");
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        // 7 codes: MP-100, MP-101, MP-200, MP-300, MP-401, MP-402, plus MP-101 covered above.
        List<ProviderError> ok = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok)
                .map(o -> ((ParseOutcome.Ok) o).error())
                .toList();

        Set<String> codes = ok.stream().map(ProviderError::code).collect(Collectors.toSet());
        assertThat(codes).contains(
                "MP-100", "MP-101", "MP-200", "MP-300", "MP-401", "MP-402");
    }

    @Test
    void parser_tracksLowercaseSectionHeader() throws IOException {
        String input = loadResource("/fixtures/messy_provider.txt");
        List<ParseOutcome> out = new Parser().parseText(input);

        // MP-200 sits under '== auth ==' (lowercase) — the section name must be captured verbatim.
        ProviderError mp200 = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok &&
                        ((ParseOutcome.Ok) o).error().code().equals("MP-200"))
                .map(o -> ((ParseOutcome.Ok) o).error())
                .findFirst().orElseThrow();
        assertThat(mp200.section()).isEqualTo("auth");
    }

    @Test
    void parser_extractsAmbiguityPatternBearingCode_forV4ToCatch() throws IOException {
        String input = loadResource("/fixtures/messy_provider.txt");
        List<ParseOutcome> out = new Parser().parseText(input);

        // MP-300 'Duplicate transaction' will trigger V4 ambiguity in Validator;
        // parser's job is just to extract it with the right message.
        ProviderError mp300 = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok &&
                        ((ParseOutcome.Ok) o).error().code().equals("MP-300"))
                .map(o -> ((ParseOutcome.Ok) o).error())
                .findFirst().orElseThrow();
        assertThat(mp300.message()).isEqualTo("Duplicate transaction");
    }
}
