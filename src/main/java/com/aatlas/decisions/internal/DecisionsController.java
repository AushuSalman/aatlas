package com.aatlas.decisions.internal;

import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealRecord;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.RecordDecisionRequest;
import com.aatlas.decisions.RecordPurchaseRequest;
import com.aatlas.decisions.RecordSaleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group L: recent decisions, one decision, and clearing a demo tenant's own applied decisions.
 *
 * <p>The three {@code POST} endpoints below are not in the wave-2 brief's Group L list - the
 * brief expects {@code sell}/{@code buy}/{@code bulk} to call {@link DecisionRecorder}
 * directly as a Java dependency once their endpoints exist. Until they are merged, nothing in
 * this worktree can create a decision or a live deal at all, so {@code /history}, {@code
 * /deals} and {@code /decisions} would only ever show the 181 seeded rows. These expose the
 * same recorder over HTTP so the module is exercisable and demoable standalone; they are not
 * a replacement for the direct Java call other tracks will make.
 */
@RestController
@RequestMapping(path = "/api/v1/decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Decisions", description = "What a user actually did: applied recommendations.")
class DecisionsController {

    private final DecisionRecorderImpl recorder;

    DecisionsController(DecisionRecorderImpl recorder) {
        this.recorder = recorder;
    }

    @Operation(summary = "Record a decision",
            description = "Not in the wave-2 brief's endpoint list - see the class javadoc. Mirrors "
                    + "DecisionRecorder.record(...), the call sell/buy/bulk will make directly once merged.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Decision record(@Valid @RequestBody RecordDecisionRequest request) {
        return recorder.record(request);
    }

    @Operation(summary = "Record a sell-side deal", description = "See the class javadoc.")
    @PostMapping(path = "/sale", consumes = MediaType.APPLICATION_JSON_VALUE)
    DealRecord recordSale(@Valid @RequestBody RecordSaleRequest request) {
        return recorder.recordSale(request);
    }

    @Operation(summary = "Record a buy-side deal, badging a real purchase order when supplierId/destinationId are given",
            description = "See the class javadoc.")
    @PostMapping(path = "/purchase", consumes = MediaType.APPLICATION_JSON_VALUE)
    DealRecord recordPurchase(@Valid @RequestBody RecordPurchaseRequest request) {
        return recorder.recordPurchase(request);
    }

    @Operation(summary = "Recent decisions, newest first, keyset paged")
    @GetMapping
    CursorPage<Decision> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return recorder.list(limit, cursor);
    }

    @Operation(summary = "One decision with its deals")
    @GetMapping("/{id}")
    DecisionDetail get(@PathVariable UUID id) {
        Decision decision = recorder.get(id);
        return new DecisionDetail(decision, recorder.dealsFor(id));
    }

    record DecisionDetail(Decision decision, java.util.List<com.aatlas.decisions.DealRecord> deals) {
    }

    @Operation(summary = "Clear my applied decisions",
            description = "Demo tenants only: deletes this user's own decision rows. Deals already recorded stay.")
    @DeleteMapping("/mine")
    DeleteResponse clearMine() {
        return new DeleteResponse(recorder.clearMine());
    }

    record DeleteResponse(int deleted) {
    }
}
