package com.aatlas.assistant.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

final class AssistantDtos {

    private AssistantDtos() {
    }

    @Schema(name = "AskRequest")
    record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    @Schema(name = "AssistantCta")
    record CtaView(String label, String href) {
    }

    @Schema(name = "AssistantAnswerLine")
    record LineView(String label, String value, @Schema(allowableValues = {"good", "bad", "muted"}) String tone) {
    }

    /** Field-for-field port of {@code src/lib/intel/assistant.ts}'s {@code AssistantAnswer}. */
    @Schema(name = "AssistantAnswer")
    record AnswerView(
            String title,
            String headline,
            List<LineView> lines,
            String summary,
            CtaView cta,
            CtaView secondary) {
    }

    @Schema(name = "AssistantHistoryEntry")
    record HistoryEntryView(String question, Instant askedAt) {
    }
}
