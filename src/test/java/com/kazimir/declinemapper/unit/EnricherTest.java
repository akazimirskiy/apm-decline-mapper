package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.model.Category;
import com.kazimir.declinemapper.model.RetryStrategy;
import com.kazimir.declinemapper.stage.Enricher;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EnricherTest {

    private final String prompt = new Enricher().systemPrompt();

    // ---- Test #6: all 7 categories present ----

    @Test
    void prompt_containsAllSevenCategories() {
        for (Category c : Category.values()) {
            assertThat(prompt).as("category " + c.name() + " must be in the prompt")
                    .contains(c.name());
        }
        assertThat(Enricher.coversAllCategories(prompt)).isTrue();
    }

    @Test
    void prompt_containsAllFourRetryStrategies() {
        // Retry strategy values use lowercase snake_case in JSON form.
        assertThat(prompt).contains("no_retry");
        assertThat(prompt).contains("retry_with_backoff");
        assertThat(prompt).contains("retry_after_fix");
        assertThat(prompt).contains("no_action");
        assertThat(RetryStrategy.values()).hasSize(4);
    }

    // ---- Test #7: operational confidence rubric verbatim ----

    @Test
    void prompt_containsConfidenceRubric_verbatim() {
        assertThat(prompt).contains("Operational Confidence Rubric");
        assertThat(prompt).contains("exact match to one taxonomy category");
        assertThat(prompt).contains("no plausible second category");
        assertThat(prompt).contains("one primary category is clear");
        assertThat(prompt).contains("a secondary category is plausible");
        assertThat(prompt).contains("two or more categories are plausible");
        assertThat(prompt).contains("provider message is vague");
    }

    // ---- Test #8: no QP-coded examples (no QuickPay anchoring) ----

    @Test
    void prompt_containsNoQuickPayCodedExamples() {
        Pattern qpAnchor = Pattern.compile("\\bQP-\\d+\\b");
        assertThat(qpAnchor.matcher(prompt).find())
                .as("system prompt must not anchor downstream providers to QuickPay codes")
                .isFalse();
    }

    @Test
    void prompt_usesSyntheticNeutralFewShots() {
        // The two synthetic codes from the design — XX-001 (clear) and YY-042 (ambiguous).
        assertThat(prompt).contains("XX-001");
        assertThat(prompt).contains("YY-042");
        // Both example outputs must demonstrate the schema fields.
        assertThat(prompt).contains("\"COMMON_DECLINE\"");
        assertThat(prompt).contains("\"high\"");
        assertThat(prompt).contains("\"low\"");
        assertThat(prompt).contains("needs_human_review: true");
        assertThat(prompt).contains("needs_human_review: false");
    }

    @Test
    void prompt_endsWithHardInstruction_thatPreventsHallucinatedCategories() {
        assertThat(prompt).contains("only via the provided");
        // text block wraps; assert each side of the line break separately
        assertThat(prompt).contains("strictly");
        assertThat(prompt).contains("from the seven values");
        assertThat(prompt).contains("Do not invent categories");
    }

    // ---- Determinism: same Enricher instance → same prompt every call ----

    @Test
    void systemPrompt_isDeterministic() {
        Enricher e = new Enricher();
        assertThat(e.systemPrompt()).isEqualTo(e.systemPrompt());
    }

    @Test
    void promptTemplateVersion_isPresent() {
        // Used downstream in the cache-key hash; must be a non-empty string constant.
        assertThat(Enricher.PROMPT_TEMPLATE_VERSION).isNotBlank();
    }
}
