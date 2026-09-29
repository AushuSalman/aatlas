package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aatlas.competition.internal.CompetitionDtos.RefreshRow;
import com.aatlas.notifications.Notifications;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RefreshJobRunnerTest {

    private final RefreshJobs jobs = mock(RefreshJobs.class);
    private final CompetitionService competition = mock(CompetitionService.class);
    private final Notifications notifications = mock(Notifications.class);
    @SuppressWarnings("unchecked")
    private final RefreshJobRunner runner = new RefreshJobRunner(mock(ObjectProvider.class), jobs, competition,
            notifications);

    private final UUID tenant = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    private void job(String trigger, List<String> items) {
        when(jobs.get(tenant, id)).thenReturn(new RefreshJobs.Job(id, trigger, "queued", List.of("ebay"), items,
                items.size(), 0, 0, 0, 0, null, null, null, null, null));
    }

    private static RefreshRow row(String item, String status, int kept) {
        return new RefreshRow(item, item, status, kept, kept, null, null, null);
    }

    @Test
    void pricesEveryItemWritesProgressAndTellsTheBell() {
        job("setup", List.of("A-1", "B-2", "C-3"));
        ShoppingProvider ebay = mock(ShoppingProvider.class);
        when(ebay.label()).thenReturn("eBay (Browse API)");
        when(competition.providersFor(List.of("ebay"))).thenReturn(List.of(ebay));
        when(competition.refreshItem(eq("A-1"), anyList(), anyList())).thenReturn(row("A-1", "saved", 1));
        when(competition.refreshItem(eq("B-2"), anyList(), anyList())).thenReturn(row("B-2", "nothing-kept", 0));
        when(competition.refreshItem(eq("C-3"), anyList(), anyList())).thenThrow(new IllegalStateException("boom"));

        runner.execute(tenant, id);

        verify(jobs).started(tenant, id);
        verify(jobs).progress(tenant, id, 3, 1, 1, 1);
        verify(jobs).finished(tenant, id, "done", null);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(notifications).publish(eq(tenant), eq("competitor-prices"), eq("Competitor prices updated"),
                body.capture(), link.capture());
        assertThat(body.getValue()).startsWith("1 of 3 products now have competitor prices from eBay (Browse API).")
                .contains("1 could not be searched");
        assertThat(link.getValue()).isEqualTo("/app/sell?panel=competition&item=A-1");
    }

    @Test
    void noAvailableSourceFailsTheJobAndSaysSo() {
        job("import", List.of("A-1"));
        when(competition.providersFor(any())).thenReturn(List.of());

        runner.execute(tenant, id);

        verify(jobs).finished(eq(tenant), eq(id), eq("failed"), any());
        verify(notifications).publish(eq(tenant), eq("competitor-prices"), eq("Competitor prices could not be fetched"),
                any(), eq("/app/settings#price-sources"));
    }
}
