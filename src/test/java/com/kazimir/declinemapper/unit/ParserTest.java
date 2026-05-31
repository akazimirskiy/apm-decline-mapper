package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.model.GarbageKind;
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

class ParserTest {

    private static String loadResource(String path) throws IOException {
        try (InputStream in = ParserTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- Test #1: QuickPay → 26 Ok outcomes, 0 LLM calls ----
    // (PDF contains 10+4+5+4+3 = 26 codes across the five sections; the design docs'
    //  earlier "24" figure was a miscount, corrected here against the real fixture.)

    @Test
    void parser_parsesAllCodes_andUsesOnlyStateMachine_whenInputIsQuickPay() throws IOException {
        String input = loadResource("/fixtures/quickpay_v2.4.txt");
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        long okCount = out.stream().filter(o -> o instanceof ParseOutcome.Ok).count();
        long garbageCount = out.stream().filter(o -> o instanceof ParseOutcome.Garbage).count();

        assertThat(okCount).isEqualTo(26);
        assertThat(garbageCount).isEqualTo(0);

        // Spot-check specific codes
        Set<String> codes = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok)
                .map(o -> ((ParseOutcome.Ok) o).error().code())
                .collect(Collectors.toSet());
        assertThat(codes).contains("QP-001", "QP-008", "QP-009", "QP-103", "QP-200", "QP-401");

        // Sections are tracked correctly.
        ProviderError qp008 = ((ParseOutcome.Ok) out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok &&
                        ((ParseOutcome.Ok) o).error().code().equals("QP-008"))
                .findFirst().orElseThrow()).error();
        assertThat(qp008.section()).isEqualTo("Transaction Processing Errors");
        assertThat(qp008.message()).isEqualTo("Do not honor");

        ProviderError qp103 = ((ParseOutcome.Ok) out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok &&
                        ((ParseOutcome.Ok) o).error().code().equals("QP-103"))
                .findFirst().orElseThrow()).error();
        assertThat(qp103.section()).isEqualTo("System / Connectivity Errors");
        assertThat(qp103.message()).isEqualTo("Rate limit exceeded");
    }

    // ---- Test #3: snake_case code with extended anchor ----

    @Test
    void parser_extractsCode_whenAnchorIsSnakeCase() {
        String input = """
                == System ==

                ERR_RATE_LIMIT "API rate limit"
                    Too many requests in the last minute.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ParseOutcome.Ok.class);
        ProviderError pe = ((ParseOutcome.Ok) out.get(0)).error();
        assertThat(pe.code()).isEqualTo("ERR_RATE_LIMIT");
        assertThat(pe.message()).isEqualTo("API rate limit");
        assertThat(pe.section()).isEqualTo("System");
    }

    @Test
    void parser_extractsCode_whenAnchorIsMixedKebabAndSnake() {
        String input = """
                PG-CARD-EXPIRED "Card expired"
                    The card's expiry date has passed.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);
        assertThat(((ParseOutcome.Ok) out.get(0)).error().code()).isEqualTo("PG-CARD-EXPIRED");
    }

    // ---- Test #4: numeric-only code ----

    @Test
    void parser_extractsCode_whenCodeIsNumericOnly() {
        // The "05" code is a real-world thing (ISO 8583); state machine accepts it
        // because the line shape "<digits> \"<message>\"" is unambiguous.
        String input = """
                05 "Pickup card"
                    Card flagged by issuer network. error_code: 05
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);
        ProviderError pe = ((ParseOutcome.Ok) out.get(0)).error();
        assertThat(pe.code()).isEqualTo("05");
        assertThat(pe.message()).isEqualTo("Pickup card");
    }

    // ---- Test #5: anchor mismatch → stderr warning, not error ----

    @Test
    void parser_warns_whenAnchorCountExceedsParserOutcomes() {
        String input = """
                === Cross-Refs ===

                XYZ-001 "Main code"
                    See XYZ-002 for details. Also see ABC-DEF and Retry-After header.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);   // only XYZ-001 should be a real code
        assertThat(p.getWarnings()).isNotEmpty();
        assertThat(p.getWarnings().get(0))
                .contains("anchor scan found")
                .contains("cross-references");
    }

    // ---- Test #30: empty input → Garbage(EMPTY_INPUT) ----

    @Test
    void parser_emitsGarbage_whenInputIsEmpty() {
        Parser p = new Parser();
        assertThat(p.parseText(""))
                .singleElement()
                .isInstanceOfSatisfying(ParseOutcome.Garbage.class, g ->
                        assertThat(g.kind()).isEqualTo(GarbageKind.EMPTY_INPUT));
    }

    @Test
    void parser_emitsGarbage_whenInputIsWhitespaceOnly() {
        Parser p = new Parser();
        assertThat(p.parseText("   \n\n\t  \n"))
                .singleElement()
                .isInstanceOfSatisfying(ParseOutcome.Garbage.class, g ->
                        assertThat(g.kind()).isEqualTo(GarbageKind.EMPTY_INPUT));
    }

    @Test
    void parser_emitsGarbage_whenInputHasNoRecognizableCodes() {
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText("This is just prose with no codes at all.\n");
        assertThat(out).singleElement()
                .isInstanceOfSatisfying(ParseOutcome.Garbage.class, g ->
                        assertThat(g.kind()).isEqualTo(GarbageKind.EMPTY_INPUT));
    }

    // ---- Test #32: code with no quoted message → Garbage(NO_MESSAGE) ----

    @Test
    void parser_emitsNoMessageGarbage_whenCodeHasNoQuotedMessage_andOthersStillSucceed() {
        String input = """
                NOMSG-001
                    A code without a quoted message.

                GOOD-001 "Has a message"
                    This one is fine.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(2);
        ParseOutcome bad = out.stream()
                .filter(o -> o instanceof ParseOutcome.Garbage g && g.code().equals("NOMSG-001"))
                .findFirst().orElseThrow();
        assertThat(((ParseOutcome.Garbage) bad).kind()).isEqualTo(GarbageKind.NO_MESSAGE);
        assertThat(((ParseOutcome.Garbage) bad).detail()).contains("trailing text");

        ParseOutcome good = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok ok && ok.error().code().equals("GOOD-001"))
                .findFirst().orElseThrow();
        assertThat(((ParseOutcome.Ok) good).error().message()).isEqualTo("Has a message");
    }

    // ---- Test #33: duplicate identical → dedup with warning ----

    @Test
    void parser_dedupsIdenticalDuplicate_andWarns() {
        String input = """
                DUP-001 "Same message"
                    Same description here.

                DUP-001 "Same message"
                    Same description here.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ParseOutcome.Ok.class);
        assertThat(p.getWarnings()).anyMatch(w -> w.contains("duplicate code DUP-001"));
    }

    // ---- Test #34: duplicate with conflicting descriptions → Garbage(DUPLICATE_CONFLICT) for both ----

    @Test
    void parser_emitsDuplicateConflict_whenSameCodeHasDifferentDescriptions() {
        String input = """
                CONF-001 "First description"
                    Description A.

                CONF-001 "Second description"
                    Description B.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        // both occurrences come back as Garbage with DUPLICATE_CONFLICT
        long conflictCount = out.stream()
                .filter(o -> o instanceof ParseOutcome.Garbage g
                        && g.code().equals("CONF-001")
                        && g.kind() == GarbageKind.DUPLICATE_CONFLICT)
                .count();
        assertThat(conflictCount).isEqualTo(2);
    }

    // ---- Test #35: cross-ref code inside description doesn't get duplicated ----

    @Test
    void parser_doesNotDuplicate_whenCodeLikeStringAppearsInsideDescription() {
        String input = """
                XR-001 "Main code"
                    Related: XR-002, XR-003. See also Retry-After header.
                """;
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        // Only XR-001 is a real code declaration; XR-002, XR-003 are cross-refs.
        long okCount = out.stream().filter(o -> o instanceof ParseOutcome.Ok).count();
        assertThat(okCount).isEqualTo(1);
        // Anchor mismatch should produce a warning.
        assertThat(p.getWarnings()).isNotEmpty();
    }

    // ---- Test #36: description > 5000 chars → Garbage(DESCRIPTION_TOO_LONG) ----

    @Test
    void parser_emitsTooLongGarbage_whenDescriptionExceeds5000Chars() {
        String longText = "x".repeat(6000);
        String input = "BIG-001 \"Big description\"\n    " + longText + "\n";
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOfSatisfying(ParseOutcome.Garbage.class, g -> {
            assertThat(g.code()).isEqualTo("BIG-001");
            assertThat(g.kind()).isEqualTo(GarbageKind.DESCRIPTION_TOO_LONG);
        });
    }

    // ---- Test #37: mixed line endings → normalized ----

    @Test
    void parser_normalizesMixedLineEndings() {
        String input =
                "CRLF-001 \"CRLF\"\r\n" +
                "    Description with CRLF endings.\r\n" +
                "\r\n" +
                "CR-001 \"old-Mac CR\"\r" +
                "    Description with CR endings.\r" +
                "\r" +
                "LF-001 \"unix LF\"\n" +
                "    Description with LF endings.\n";
        Parser p = new Parser();
        List<ParseOutcome> out = p.parseText(input);

        Set<String> codes = out.stream()
                .filter(o -> o instanceof ParseOutcome.Ok)
                .map(o -> ((ParseOutcome.Ok) o).error().code())
                .collect(Collectors.toSet());
        assertThat(codes).containsExactlyInAnyOrder("CRLF-001", "CR-001", "LF-001");
    }

    // ---- NON_UTF8 path (covered indirectly via parse(byte[])) ----

    @Test
    void parser_emitsGarbage_whenInputIsNotUtf8() {
        // 0xff 0xfe is a UTF-16 BOM and not valid UTF-8.
        byte[] junk = new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xff, (byte) 0xff, 0x00, 0x01, 0x02};
        Parser p = new Parser();
        List<ParseOutcome> out = p.parse(junk);

        assertThat(out).singleElement()
                .isInstanceOfSatisfying(ParseOutcome.Garbage.class, g ->
                        assertThat(g.kind()).isEqualTo(GarbageKind.NON_UTF8));
    }
}
