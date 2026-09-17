#!/usr/bin/env node
// Deterministic alternate sales-history sample: a different distributor (electrical, not
// plumbing/HVAC), different items, prices, branches, customers and date window, so a
// side-by-side upload against sales-history.csv visibly changes every number on screen.
// Same column shape as sales-history.csv, so it needs no new mapping to import.
//
// Run: node generate-sales-history-alt.js
// Writes sales-history-alt.csv next to this script.

const fs = require('fs');
const path = require('path');

// FNV-1a, same technique as generate-sample-data.js, so re-running this script reproduces
// the file byte for byte.
function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}
function rand(key) {
  return fnv1a(key) / 4294967296;
}

const ITEMS = [
  { item: 'BW200110', desc: '12 AWG THHN COPPER WIRE 500FT SPOOL', category: 'Wire & Cable', basePrice: 189.5, baseCost: 128.0, monthly: 340 },
  { item: 'BW200115', desc: '10 AWG THHN COPPER WIRE 500FT SPOOL', category: 'Wire & Cable', basePrice: 261.0, baseCost: 179.0, monthly: 210 },
  { item: 'BW311220', desc: '20A SINGLE POLE CIRCUIT BREAKER', category: 'Breakers & Panels', basePrice: 14.25, baseCost: 8.1, monthly: 890 },
  { item: 'BW311240', desc: '40A DOUBLE POLE CIRCUIT BREAKER', category: 'Breakers & Panels', basePrice: 31.75, baseCost: 19.4, monthly: 260 },
  { item: 'BW311500', desc: '200A MAIN BREAKER LOAD CENTER 40-SPACE', category: 'Breakers & Panels', basePrice: 214.0, baseCost: 151.0, monthly: 45 },
  { item: 'BW422031', desc: '3/4 IN EMT CONDUIT 10FT', category: 'Conduit & Fittings', basePrice: 9.85, baseCost: 6.2, monthly: 1450 },
  { item: 'BW422045', desc: '1 IN EMT CONDUIT 10FT', category: 'Conduit & Fittings', basePrice: 13.4, baseCost: 8.75, monthly: 980 },
  { item: 'BW422900', desc: '4X4 METAL JUNCTION BOX', category: 'Conduit & Fittings', basePrice: 4.1, baseCost: 2.35, monthly: 1600 },
  { item: 'BW533100', desc: 'DECORA 15A DUPLEX RECEPTACLE', category: 'Devices & Outlets', basePrice: 3.6, baseCost: 1.9, monthly: 2100 },
  { item: 'BW645210', desc: '4FT LED SHOP LIGHT FIXTURE 4000LM', category: 'Lighting', basePrice: 38.5, baseCost: 24.6, monthly: 310 },
];

const BRANCHES = [
  { code: '200410', label: 'Atlanta' },
  { code: '200420', label: 'Houston' },
  { code: '200430', label: 'Phoenix' },
  { code: '200440', label: 'Denver' },
  { code: '200450', label: 'Seattle' },
];

const CUSTOMERS = [
  'Ferro Electrical Contractors', 'BrightLine Electric Co', 'Summit Industrial Wiring',
  'Vector Electrical Services', 'Highline Power & Controls', 'Cascade Electric Supply Co',
  'Redwood Electrical Contractors', 'Paragon Electric LLC',
];

// 12 months ending this month, so the window sits visibly apart from sales-history.csv's
// 2024-07..2026-08 run.
const MONTHS = 12;
const today = new Date();
const startMonth = new Date(today.getFullYear(), today.getMonth() - (MONTHS - 1), 1);

function monthKey(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

// Mild seasonality: Lighting and Wire & Cable pick up in spring/summer build season;
// Breakers & Panels stay flat year-round (replacement/retrofit demand, not new-build).
function seasonalFactor(category, monthIndex0) {
  const month = (startMonth.getMonth() + monthIndex0) % 12; // 0=Jan
  const buildSeason = month >= 2 && month <= 7; // Mar-Aug
  if (category === 'Lighting' || category === 'Wire & Cable') return buildSeason ? 1.35 : 0.85;
  if (category === 'Conduit & Fittings') return buildSeason ? 1.2 : 0.9;
  return 1.0;
}

const rows = [['Item No', 'Item Description', 'Invoice Date', 'Qty Shipped', 'Net Price', 'Unit Cost', 'Bill To', 'Whse']];

for (let m = 0; m < MONTHS; m++) {
  const monthDate = new Date(startMonth.getFullYear(), startMonth.getMonth() + m, 1);
  const daysInMonth = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0).getDate();

  for (const it of ITEMS) {
    const seasonal = seasonalFactor(it.category, m);
    // Cost drifts up ~0.6%/month; price follows more slowly, so margin compresses a little
    // over the window - a real trend for the margin/forecast engines to find.
    const costThisMonth = it.baseCost * (1 + 0.006 * m);
    const priceThisMonth = it.basePrice * (1 + 0.004 * m);

    const targetUnits = Math.round(it.monthly * seasonal);
    const invoiceCount = Math.max(4, Math.round(targetUnits / 6));

    for (let i = 0; i < invoiceCount; i++) {
      const key = `${it.item}-${monthKey(monthDate)}-${i}`;
      const day = 1 + Math.floor(rand(key + 'd') * daysInMonth);
      const date = new Date(monthDate.getFullYear(), monthDate.getMonth(), day);
      const qty = Math.max(1, Math.round(2 + rand(key + 'q') * 10));
      const priceJitter = 0.97 + rand(key + 'p') * 0.06; // +/-3%
      const price = Math.round(priceThisMonth * priceJitter * 100) / 100;
      const cost = Math.round(costThisMonth * 100) / 100;
      const branch = BRANCHES[Math.floor(rand(key + 'b') * BRANCHES.length)].code;
      const customer = CUSTOMERS[Math.floor(rand(key + 'c') * CUSTOMERS.length)];
      const iso = date.toISOString().slice(0, 10);
      rows.push([it.item, it.desc, iso, String(qty), price.toFixed(2), cost.toFixed(2), customer, branch]);
    }
  }
}

const csv = rows.map((r) => r.map((v) => (/[,"]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)).join(',')).join('\n') + '\n';
const out = path.join(__dirname, 'sales-history-alt.csv');
fs.writeFileSync(out, csv);
console.log(`Wrote ${out}`);
console.log(`${rows.length - 1} rows, ${ITEMS.length} items, ${BRANCHES.length} branches, ${MONTHS} months (${monthKey(startMonth)}..${monthKey(new Date(startMonth.getFullYear(), startMonth.getMonth() + MONTHS - 1, 1))})`);
