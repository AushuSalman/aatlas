# Sample import data

A realistic dataset for exercising the import path and every module that reads it. Used to
verify the whole app end to end on 2026-09-16.

| File | Rows | What it is |
|---|---|---|
| `sales-history.csv` | 11,858 | 26 months of invoice lines, Aug 2024 – Sep 2026 |
| `suppliers.csv` | 10 | a supplier panel in a real ERP export's column names |
| `generate-sample-data.js` | — | rebuilds both, byte for byte |

The company is *Hardin Supply Co*, a US plumbing and HVAC distributor: 17 SKUs across 9
branches and 15 customers, about $22m of revenue.

## What it is built to exercise

- **Seasonality.** AC condensers and PVC peak in July; water heaters and thermostats in
  January. Flat-selling items (copper tube, brass valves) ignore the month, so a trend line
  has something to find and something to ignore.
- **Cost drift.** Every item's cost rises over the window, copper and equipment faster than
  plastic, so margin is not constant.
- **Items the catalogue does not have.** `HRD551200` and `HRD551201` are absent from
  `seed/products.json`, so a commit has to create them — which is what proves the import
  creates products rather than only matching them. The commit log says `2 products created`.
- **Items that start mid-window.** The two new SKUs and the three `HRD9000xx` ones begin
  partway through, so "since when" is a real question.

Nothing here is random. Every figure comes from an FNV-1a hash of a stable key, the same
technique `common.seed.Seeded` uses, so re-running the generator reproduces the files
exactly and a re-import compares cleanly against the last one.

## Loading it

```bash
# 1. sign up, then connect the sample catalogue (gives categories, regions and the panel;
#    an import alone leaves products 'uncategorised' and branches 'unassigned')
curl -X POST localhost:8080/api/v1/data-sources -H "Authorization: Bearer $T" \
     -H 'Content-Type: application/json' -d '{"kind":"sample"}'

# 2. suppliers - one call, validates and writes
curl -X POST localhost:8080/api/v1/suppliers/imports -H "Authorization: Bearer $T" \
     -F "file=@docs/samples/suppliers.csv;type=text/csv"

# 3. sales - upload, then commit the returned id
curl -X POST localhost:8080/api/v1/imports -H "Authorization: Bearer $T" \
     -F "file=@docs/samples/sales-history.csv;type=text/csv"
curl -X POST localhost:8080/api/v1/imports/$ID/commit -H "Authorization: Bearer $T"
```

The commit is asynchronous: poll `GET /api/v1/imports/$ID` until `status` leaves
`COMMITTING`. On this machine it took about seven seconds.

Step 1 matters. `SalesTransactionLoader` deliberately declines to guess a category or a
region for a row it invents, so a tenant built from an import alone has every product
`uncategorised` and every branch `unassigned` — and the region-driven reads (buy, insights)
have nothing to group by.

## Regenerating

```bash
node docs/samples/generate-sample-data.js
```

Writes both CSVs next to the script. Edit `PRODUCTS`, `BRANCHES` or `CUSTOMERS` at the top
to change the shape of the data.

## What it showed

Loading this found a crash: `GET /insights/demographics` returned 500 for any tenant with an
imported product, because `DemographicsEngine` pre-sized its accumulator to four hardcoded
categories and read `null` for `uncategorised`. One imported SKU was enough. Fixed.

It also showed the limit of the current engines. Revenue and cost on every screen are
derived from a hash of the item number, not from these rows — a tenant with all 11,858 rows
and one with none report the identical `totalRevenue` of 22,386,464. What the import does
change is which (item, branch) pairs are priceable, via `product_stores`. See
`decisions.md` on `PricingCost`, "the one real stand-in".
