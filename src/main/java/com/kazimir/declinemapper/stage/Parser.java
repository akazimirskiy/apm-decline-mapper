package com.kazimir.declinemapper.stage;

import com.kazimir.declinemapper.model.GarbageKind;
import com.kazimir.declinemapper.model.ParseOutcome;
import com.kazimir.declinemapper.model.ParsePath;
import com.kazimir.declinemapper.model.ProviderError;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 — deterministic-first parser.
 *
 * <p>Strategy:
 * <ol>
 *     <li>Validate UTF-8 of the input bytes; non-UTF-8 → {@link GarbageKind#NON_UTF8}.</li>
 *     <li>Normalize line endings (CR/CRLF/LF → LF).</li>
 *     <li>State machine over lines: section headers, code-with-message at left margin,
 *         bare codes (no message), indented description continuation.</li>
 *     <li>Post-process: group by code, detect identical duplicates (warning) vs
 *         conflicting duplicates ({@link GarbageKind#DUPLICATE_CONFLICT}), check
 *         description length, emit {@link ParseOutcome}s.</li>
 *     <li>Cross-check: count unique anchor-shaped tokens in the raw text vs the
 *         number of parser outcomes. If anchors exceed outcomes, emit a warning
 *         (some anchors were probably cross-references inside descriptions —
 *         expected behaviour, but worth surfacing).</li>
 * </ol>
 *
 * <p>The {@link ParseOutcome.AmbiguousChunk} variant is reserved for chunks the
 * state machine can't extract cleanly (multi-paragraph descriptions, nested code
 * references). It is wired to an LLM fallback in Step 4; this class only emits it.
 *
 * <p>Warnings are stored in {@link #getWarnings()} for the caller to print to stderr.
 */
public final class Parser {

    public static final int MAX_DESCRIPTION_CHARS = 5000;

    /**
     * One code-like token: either alphanumeric with at least one [-_] separator
     * (matches {@code QP-001}, {@code ERR_RATE_LIMIT}, {@code do_not_honor},
     * {@code PG-CARD-EXPIRED}) or a 2–4 digit numeric (matches {@code 05}, {@code 4012}).
     */
    private static final String CODE_RE =
            "(?:[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+|\\d{2,4})";

    /** Left-margin line: code + quoted message. */
    private static final Pattern CODE_WITH_MSG = Pattern.compile(
            "^(" + CODE_RE + ")\\s+\"([^\"]+)\"\\s*$");

    /** Left-margin line: code alone, no message. */
    private static final Pattern BARE_CODE = Pattern.compile(
            "^(" + CODE_RE + ")\\s*$");

    /** Section header: {@code === Name ===}, {@code == Name ==}, {@code --- Name ---}. */
    private static final Pattern SECTION = Pattern.compile(
            "^\\s*(?:={2,}|-{2,})\\s+(.+?)\\s+(?:={2,}|-{2,})\\s*$");

    /** Anchor scan (alphanumeric form) for cross-check. */
    private static final Pattern ANCHOR_ANYWHERE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+");

    /** Numeric anchor near a keyword (cross-check only). */
    private static final Pattern NUMERIC_NEAR_KEYWORD = Pattern.compile(
            "(?i)(?:code|error|result_code)\\b[^\\n]{0,30}\\b(\\d{2,4})\\b");

    private final List<String> warnings = new ArrayList<>();

    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    public List<ParseOutcome> parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public List<ParseOutcome> parse(byte[] bytes) {
        warnings.clear();
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return List.of(new ParseOutcome.Garbage(
                    "?", GarbageKind.NON_UTF8,
                    "input is not valid UTF-8: " + e.getMessage()));
        }
        return parseText(text);
    }

    public List<ParseOutcome> parseText(String text) {
        warnings.clear();
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        if (normalized.isBlank()) {
            return List.of(new ParseOutcome.Garbage(
                    "?", GarbageKind.EMPTY_INPUT, "input is empty or whitespace"));
        }

        List<RawBlock> blocks = collectBlocks(normalized);
        List<ParseOutcome> outcomes = resolveBlocks(blocks);

        if (outcomes.isEmpty()) {
            return List.of(new ParseOutcome.Garbage(
                    "?", GarbageKind.EMPTY_INPUT, "no recognizable codes found"));
        }

        int anchors = countUniqueAnchors(normalized);
        if (anchors > outcomes.size()) {
            warnings.add("anchor scan found " + anchors + " unique code-shaped tokens, "
                    + "parser produced " + outcomes.size() + " outcomes "
                    + "(some anchors were likely cross-references inside descriptions)");
        }

        return outcomes;
    }

    // ---- State machine ----

    private record RawBlock(String code, String message, String description, String section) {
    }

    private static List<RawBlock> collectBlocks(String text) {
        List<RawBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        String currentSection = "";
        String pendingCode = null;
        String pendingMsg = null;
        StringBuilder pendingDesc = new StringBuilder();
        String pendingBareCode = null;

        for (String line : lines) {
            // Section header — flush any pending state and update section.
            Matcher s = SECTION.matcher(line);
            if (s.matches()) {
                flushFull(blocks, pendingCode, pendingMsg, pendingDesc, currentSection);
                if (pendingBareCode != null) {
                    blocks.add(new RawBlock(pendingBareCode, null, "", currentSection));
                }
                pendingCode = null;
                pendingMsg = null;
                pendingDesc = new StringBuilder();
                pendingBareCode = null;
                currentSection = s.group(1).trim();
                continue;
            }

            boolean atMargin = !line.isEmpty() && !Character.isWhitespace(line.charAt(0));

            if (atMargin) {
                Matcher c = CODE_WITH_MSG.matcher(line);
                if (c.matches()) {
                    flushFull(blocks, pendingCode, pendingMsg, pendingDesc, currentSection);
                    if (pendingBareCode != null) {
                        blocks.add(new RawBlock(pendingBareCode, null, "", currentSection));
                        pendingBareCode = null;
                    }
                    pendingCode = c.group(1);
                    pendingMsg = c.group(2);
                    pendingDesc = new StringBuilder();
                    continue;
                }

                Matcher b = BARE_CODE.matcher(line);
                if (b.matches()) {
                    flushFull(blocks, pendingCode, pendingMsg, pendingDesc, currentSection);
                    if (pendingBareCode != null) {
                        // back-to-back bare codes
                        blocks.add(new RawBlock(pendingBareCode, null, "", currentSection));
                    }
                    pendingCode = null;
                    pendingMsg = null;
                    pendingDesc = new StringBuilder();
                    pendingBareCode = b.group(1);
                    continue;
                }

                // Left-margin text that isn't a section / code / bare code:
                // treat as terminator for any current block (preamble or footer text).
                if (pendingCode != null || pendingBareCode != null) {
                    flushFull(blocks, pendingCode, pendingMsg, pendingDesc, currentSection);
                    if (pendingBareCode != null) {
                        blocks.add(new RawBlock(pendingBareCode, null, "", currentSection));
                        pendingBareCode = null;
                    }
                    pendingCode = null;
                    pendingMsg = null;
                    pendingDesc = new StringBuilder();
                }
                continue;
            }

            // Indented or blank line.
            if (pendingBareCode != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    blocks.add(new RawBlock(pendingBareCode, null, trimmed, currentSection));
                    pendingBareCode = null;
                }
                // a blank line right after a bare code is just whitespace; keep waiting.
                continue;
            }

            if (pendingCode != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (pendingDesc.length() > 0) pendingDesc.append(' ');
                    pendingDesc.append(trimmed);
                }
                // blank lines within description are paragraph breaks; we collapse them.
            }
        }

        // EOF flush
        flushFull(blocks, pendingCode, pendingMsg, pendingDesc, currentSection);
        if (pendingBareCode != null) {
            blocks.add(new RawBlock(pendingBareCode, null, "", currentSection));
        }
        return blocks;
    }

    private static void flushFull(List<RawBlock> blocks,
                                  String code,
                                  String msg,
                                  StringBuilder desc,
                                  String section) {
        if (code == null) return;
        blocks.add(new RawBlock(code, msg, desc.toString().trim(), section));
    }

    // ---- Post-process ----

    private List<ParseOutcome> resolveBlocks(List<RawBlock> blocks) {
        // Group by code, preserving first-encounter order.
        LinkedHashMap<String, List<RawBlock>> byCode = new LinkedHashMap<>();
        for (RawBlock b : blocks) {
            byCode.computeIfAbsent(b.code, k -> new ArrayList<>()).add(b);
        }

        List<ParseOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, List<RawBlock>> e : byCode.entrySet()) {
            List<RawBlock> entries = e.getValue();
            if (entries.size() == 1) {
                outcomes.add(toOutcome(entries.get(0)));
                continue;
            }
            // multiple entries with same code
            RawBlock first = entries.get(0);
            boolean allIdentical = entries.stream().allMatch(b ->
                    Objects.equals(b.message, first.message)
                            && Objects.equals(b.description, first.description));
            if (allIdentical) {
                warnings.add("duplicate code " + e.getKey()
                        + " (identical message and description), kept first occurrence");
                outcomes.add(toOutcome(first));
            } else {
                // Conflicting duplicates → every occurrence becomes Garbage.
                for (RawBlock b : entries) {
                    String detail;
                    if (b.message == null) {
                        detail = "no message; desc=\"" + truncate(b.description, 80) + "\"";
                    } else {
                        detail = "msg=\"" + b.message + "\"; desc=\"" + truncate(b.description, 80) + "\"";
                    }
                    outcomes.add(new ParseOutcome.Garbage(
                            e.getKey(), GarbageKind.DUPLICATE_CONFLICT, detail));
                }
            }
        }
        return outcomes;
    }

    private static ParseOutcome toOutcome(RawBlock b) {
        if (b.message == null) {
            String detail = b.description.isEmpty()
                    ? "code without quoted message"
                    : "code without quoted message; trailing text: \"" + truncate(b.description, 80) + "\"";
            return new ParseOutcome.Garbage(b.code, GarbageKind.NO_MESSAGE, detail);
        }
        if (b.description.length() > MAX_DESCRIPTION_CHARS) {
            return new ParseOutcome.Garbage(b.code, GarbageKind.DESCRIPTION_TOO_LONG,
                    "description length=" + b.description.length() + " > " + MAX_DESCRIPTION_CHARS);
        }
        return new ParseOutcome.Ok(new ProviderError(
                b.code, b.message, b.description, b.section, ParsePath.STATE_MACHINE));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- Anchor cross-check ----

    private static int countUniqueAnchors(String text) {
        Set<String> uniqueAnchors = new LinkedHashSet<>();
        Matcher m = ANCHOR_ANYWHERE.matcher(text);
        while (m.find()) {
            uniqueAnchors.add(m.group());
        }
        Matcher mn = NUMERIC_NEAR_KEYWORD.matcher(text);
        while (mn.find()) {
            uniqueAnchors.add(mn.group(1));
        }
        return uniqueAnchors.size();
    }
}
