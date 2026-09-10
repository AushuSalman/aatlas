package com.aatlas.catalog.internal.reference;

import com.aatlas.catalog.internal.seed.SeedFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the reference tables from the seed files at startup.
 *
 * <p>Regions and subdivisions, the logistics rate card and the commodity trend are the
 * same for every tenant, so they are not copied per tenant: they are upserted by natural
 * key once per boot. Running it again changes nothing unless a seed file changed, in
 * which case the row follows the file - which is the point of keeping them in a table
 * rather than a constant. Rows the seed no longer mentions are left alone; a rate card
 * entry is deleted deliberately, not by regenerating a JSON file.
 *
 * <p>An {@link ApplicationRunner} rather than a {@code @PostConstruct}: it needs Flyway to
 * have run, and runners fire after the context is fully built.
 */
@Component
class ReferenceDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataLoader.class);

    private final SeedFiles seeds;
    private final JdbcTemplate jdbc;

    ReferenceDataLoader(SeedFiles seeds, JdbcTemplate jdbc) {
        this.seeds = seeds;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int regions = loadRegions();
        int lanes = loadLogistics();
        int commodities = loadCommodities();
        log.info("Reference data loaded: {} regions, {} logistics rows, {} commodities", regions, lanes, commodities);
    }

    int loadRegions() {
        List<Object[]> regionRows = new ArrayList<>();
        List<Object[]> subdivisionRows = new ArrayList<>();
        for (SeedFiles.SeedCountry country : seeds.countries()) {
            int regionPosition = 0;
            for (SeedFiles.SeedRegion region : country.regions()) {
                regionRows.add(new Object[] {
                    country.code(), region.key(), region.label(), region.shortLabel(), regionPosition++
                });
                int subdivisionPosition = 0;
                for (SeedFiles.SeedSubdivision subdivision : region.subdivisions()) {
                    subdivisionRows.add(new Object[] {
                        country.code(), subdivision.code(), subdivision.name(), region.key(), subdivisionPosition++
                    });
                }
            }
        }
        jdbc.batchUpdate("""
                insert into regions (country_code, region_key, label, short_label, position)
                values (?, ?, ?, ?, ?)
                on conflict (country_code, region_key) do update
                    set label = excluded.label, short_label = excluded.short_label,
                        position = excluded.position, updated_at = now()
                """, regionRows);
        jdbc.batchUpdate("""
                insert into subdivisions (country_code, code, name, region_key, position)
                values (?, ?, ?, ?, ?)
                on conflict (country_code, code) do update
                    set name = excluded.name, region_key = excluded.region_key,
                        position = excluded.position, updated_at = now()
                """, subdivisionRows);
        return regionRows.size();
    }

    int loadLogistics() {
        SeedFiles.SeedLogistics logistics = seeds.logistics();

        List<Object[]> originRows = new ArrayList<>();
        int originPosition = 0;
        for (Map.Entry<String, SeedFiles.SeedOrigin> entry : logistics.origins().entrySet()) {
            SeedFiles.SeedOrigin origin = entry.getValue();
            originRows.add(new Object[] {
                entry.getKey(), origin.entry(), origin.mode(), origin.gateway(), origin.inboundPct(),
                origin.inboundDays(), origin.dutyPct(), origin.dutyNote(), originPosition++
            });
        }
        jdbc.batchUpdate("""
                insert into logistics_origins
                    (country, entry, mode, gateway, inbound_pct, inbound_days, duty_pct, duty_note, position)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (country) do update
                    set entry = excluded.entry, mode = excluded.mode, gateway = excluded.gateway,
                        inbound_pct = excluded.inbound_pct, inbound_days = excluded.inbound_days,
                        duty_pct = excluded.duty_pct, duty_note = excluded.duty_note,
                        position = excluded.position, updated_at = now()
                """, originRows);

        List<Object[]> laneRows = new ArrayList<>();
        int lanePosition = 0;
        for (SeedFiles.SeedLane lane : logistics.regions()) {
            String[] states = lane.states().toArray(String[]::new);
            for (String entry : lane.inlandPct().keySet()) {
                laneRows.add(new Object[] {
                    lane.key(), lane.label(), states, lanePosition, entry,
                    lane.inlandPct().get(entry), lane.inlandDays().get(entry)
                });
            }
            lanePosition++;
        }
        jdbc.batchUpdate("""
                insert into logistics_lanes
                    (region_key, region_label, states, position, entry, inland_pct, inland_days)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (region_key, entry) do update
                    set region_label = excluded.region_label, states = excluded.states,
                        position = excluded.position, inland_pct = excluded.inland_pct,
                        inland_days = excluded.inland_days, updated_at = now()
                """, laneRows);
        return originRows.size() + laneRows.size();
    }

    int loadCommodities() {
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, SeedFiles.SeedCommodity> entry : seeds.commodities().entrySet()) {
            rows.add(new Object[] {entry.getKey(), entry.getValue().label(), entry.getValue().pct90()});
        }
        jdbc.batchUpdate("""
                insert into commodities (commodity_key, label, pct90)
                values (?, ?, ?)
                on conflict (commodity_key) do update
                    set label = excluded.label, pct90 = excluded.pct90, updated_at = now()
                """, rows);
        return rows.size();
    }
}
