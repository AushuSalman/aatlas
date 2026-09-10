package com.aatlas.assistant.internal;

import com.aatlas.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask Aatlas: a question in, a decision out. Thin by rule: reads the request, hands it
 * to {@link AssistantService}. See that class's doc for which intents are real and which
 * are simplified stand-ins for another track's engine.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/assistant", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant", description = "Natural-language questions answered from the platform's own intelligence.")
class AssistantController {

    private final AssistantService service;

    AssistantController(AssistantService service) {
        this.service = service;
    }

    @Operation(summary = "Ask a question", description = "Matches one of six intents, or lists the suggested "
            + "questions when nothing matches. Recorded to this seat's history.")
    @PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    AssistantDtos.AnswerView ask(@Valid @RequestBody AssistantDtos.AskRequest request) {
        TenantContext.Actor actor = actor();
        return service.ask(actor.tenantId(), actor.userId(), request.question());
    }

    @Operation(summary = "Suggested questions for this seat",
            description = "The same six questions, reordered so this seat's own side of the business "
                    + "(sell or buy) comes first.")
    @GetMapping("/suggestions")
    List<String> suggestions() {
        TenantContext.Actor actor = actor();
        return service.suggestionsFor(actor.tenantId(), actor.role());
    }

    @Operation(summary = "This seat's recent questions", description = "Newest first.")
    @GetMapping("/history")
    List<AssistantDtos.HistoryEntryView> history(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        TenantContext.Actor actor = actor();
        return service.recentQuestions(actor.tenantId(), actor.userId(), limit);
    }

    private static TenantContext.Actor actor() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound"));
    }
}
