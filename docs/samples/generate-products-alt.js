#!/usr/bin/env node
// Product master for the sales-history-alt.csv electrical dataset: the same 10 items and 5
// branches, one row per (item, branch) so every item is explicitly assigned to every branch
// it actually sold at - the CSV path described for "assign a product to a branch".
//
// List price / unit cost are today's figures: the same base price and cost
// generate-sales-history-alt.js used, carried forward through its cost/price drift to the
// last of its 12 months, so they read as "current", not the 12-months-ago starting point.
//
// Run: node generate-products-alt.js
// Writes products-alt.csv next to this script.

const fs = require('fs');
const path = require('path');

const MONTHS = 12; // must match generate-sales-history-alt.js
const LAST_MONTH_INDEX = MONTHS - 1;

const ITEMS = [
  { item: 'BW200110', desc: '12 AWG THHN COPPER WIRE 500FT SPOOL', category: 'Wire & Cable', uom: 'spool', commodity: 'copper', basePrice: 189.5, baseCost: 128.0 },
  { item: 'BW200115', desc: '10 AWG THHN COPPER WIRE 500FT SPOOL', category: 'Wire & Cable', uom: 'spool', commodity: 'copper', basePrice: 261.0, baseCost: 179.0 },
  { item: 'BW311220', desc: '20A SINGLE POLE CIRCUIT BREAKER', category: 'Breakers & Panels', uom: 'each', commodity: '', basePrice: 14.25, baseCost: 8.1 },
  { item: 'BW311240', desc: '40A DOUBLE POLE CIRCUIT BREAKER', category: 'Breakers & Panels', uom: 'each', commodity: '', basePrice: 31.75, baseCost: 19.4 },
  { item: 'BW311500', desc: '200A MAIN BREAKER LOAD CENTER 40-SPACE', category: 'Breakers & Panels', uom: 'each', commodity: '', basePrice: 214.0, baseCost: 151.0 },
  { item: 'BW422031', desc: '3/4 IN EMT CONDUIT 10FT', category: 'Conduit & Fittings', uom: '10ft length', commodity: 'steel', basePrice: 9.85, baseCost: 6.2 },
  { item: 'BW422045', desc: '1 IN EMT CONDUIT 10FT', category: 'Conduit & Fittings', uom: '10ft length', commodity: 'steel', basePrice: 13.4, baseCost: 8.75 },
  { item: 'BW422900', desc: '4X4 METAL JUNCTION BOX', category: 'Conduit & Fittings', uom: 'each', commodity: '', basePrice: 4.1, baseCost: 2.35 },
  { item: 'BW533100', desc: 'DECORA 15A DUPLEX RECEPTACLE', category: 'Devices & Outlets', uom: 'each', commodity: '', basePrice: 3.6, baseCost: 1.9 },
  { item: 'BW645210', desc: '4FT LED SHOP LIGHT FIXTURE 4000LM', category: 'Lighting', uom: 'each', commodity: '', basePrice: 38.5, baseCost: 24.6 },
];

const BRANCHES = ['200410', '200420', '200430', '200440', '200450'];

const round2 = (n) => Math.round(n * 100) / 100;
// Same drift as generate-sales-history-alt.js's monthly loop, carried to its last month.
const currentPrice = (base) => round2(base * (1 + 0.004 * LAST_MONTH_INDEX));
const currentCost = (base) => round2(base * (1 + 0.006 * LAST_MONTH_INDEX));

const rows = [['Item No', 'Description', 'Category', 'Subcategory', 'UOM', 'Commodity', 'List Price', 'Unit Cost', 'Branch']];
for (const it of ITEMS) {
  const price = currentPrice(it.basePrice);
  const cost = currentCost(it.baseCost);
  for (const branch of BRANCHES) {
    rows.push([it.item, it.desc, it.category, '', it.uom, it.commodity, price.toFixed(2), cost.toFixed(2), branch]);
  }
}

const csv = rows.map((r) => r.map((v) => (/[,"]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)).join(',')).join('\n') + '\n';
const out = path.join(__dirname, 'products-alt.csv');
fs.writeFileSync(out, csv);
console.log(`Wrote ${out}`);
console.log(`${rows.length - 1} rows, ${ITEMS.length} items x ${BRANCHES.length} branches`);
