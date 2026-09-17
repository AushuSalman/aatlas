#!/usr/bin/env node
// Competitor observations for the sales-history-alt.csv electrical dataset: 2-3 competitors
// per item, recent (within the API's 180-day window), anchored to a real branch code so the
// pricing wizard and the Sell "market price" anchor have something to blend against.
//
// Run: node generate-competitor-prices-alt.js
// Writes competitor-prices-alt.csv next to this script.

const fs = require('fs');
const path = require('path');

function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}
const rand = (key) => fnv1a(key) / 4294967296;

// Current price/cost, matching generate-products-alt.js's "today" figures.
const ITEMS = [
  { item: 'BW200110', price: 197.84 },
  { item: 'BW200115', price: 272.48 },
  { item: 'BW311220', price: 14.88 },
  { item: 'BW311240', price: 33.15 },
  { item: 'BW311500', price: 223.42 },
  { item: 'BW422031', price: 10.28 },
  { item: 'BW422045', price: 13.99 },
  { item: 'BW422900', price: 4.28 },
  { item: 'BW533100', price: 3.76 },
  { item: 'BW645210', price: 40.19 },
];
const BRANCHES = ['200410', '200420', '200430', '200440', '200450'];
const COMPETITORS = ['Grid Supply Depot', 'Ampere Wholesale', 'Coastal Electrical Supply'];

const today = new Date();

const rows = [['Item No', 'Competitor', 'Price', 'Region/Branch', 'Observed Date']];
for (const it of ITEMS) {
  const n = 2 + Math.floor(rand(it.item + 'n') * 2); // 2 or 3 observations
  for (let i = 0; i < n; i++) {
    const key = `${it.item}-comp-${i}`;
    const competitor = COMPETITORS[Math.floor(rand(key + 'c') * COMPETITORS.length)];
    const branch = BRANCHES[Math.floor(rand(key + 'b') * BRANCHES.length)];
    const gap = -0.06 + rand(key + 'g') * 0.12; // +/-6% of our price
    const price = Math.round(it.price * (1 + gap) * 100) / 100;
    const daysAgo = Math.floor(rand(key + 'd') * 60); // within the last 60 days
    const observed = new Date(today.getTime() - daysAgo * 86400000);
    rows.push([it.item, competitor, price.toFixed(2), branch, observed.toISOString().slice(0, 10)]);
  }
}

const csv = rows.map((r) => r.map((v) => (/[,"]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)).join(',')).join('\n') + '\n';
const out = path.join(__dirname, 'competitor-prices-alt.csv');
fs.writeFileSync(out, csv);
console.log(`Wrote ${out}`);
console.log(`${rows.length - 1} observations, ${ITEMS.length} items, ${COMPETITORS.length} competitors`);
