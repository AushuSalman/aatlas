#!/usr/bin/env node
// Purchase history for the sales-history-alt.csv electrical dataset: the same 10 items and
// 5 branches, bought from 3 suppliers across 3 countries, most received, a few still in
// transit or open - so the buy engine has an incumbent, a landed cost and an on-time record
// to compute from, the same way sales-history-alt.csv gives the sell engine one.
//
// Run: node generate-purchases-alt.js
// Writes purchase-history-alt.csv next to this script.

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

const ITEMS = [
  { item: 'BW200110', cost: 128.0 },
  { item: 'BW200115', cost: 179.0 },
  { item: 'BW311220', cost: 8.1 },
  { item: 'BW311240', cost: 19.4 },
  { item: 'BW311500', cost: 151.0 },
  { item: 'BW422031', cost: 6.2 },
  { item: 'BW422045', cost: 8.75 },
  { item: 'BW422900', cost: 2.35 },
  { item: 'BW533100', cost: 1.9 },
  { item: 'BW645210', cost: 24.6 },
];
const BRANCHES = ['200410', '200420', '200430', '200440', '200450'];
const SUPPLIERS = [
  { name: 'Volt Distribution Co', country: 'USA', freightPct: 0.02, dutyPct: 0, leadDays: 9 },
  { name: 'Meridian Electrical Supply', country: 'Mexico', freightPct: 0.035, dutyPct: 0.01, leadDays: 14 },
  { name: 'Anhui Wire & Cable', country: 'China', freightPct: 0.06, dutyPct: 0.025, leadDays: 32 },
];

const MONTHS = 12;
const today = new Date();
const startMonth = new Date(today.getFullYear(), today.getMonth() - (MONTHS - 1), 1);

const rows = [['PO Number', 'Order Date', 'Supplier', 'Supplier Country', 'Item No', 'Qty Ordered', 'Unit Cost', 'Freight', 'Duty', 'Ship To', 'Promised Date', 'Received Date', 'Qty Received']];
let poSeq = 1;

for (let m = 0; m < MONTHS; m++) {
  const monthDate = new Date(startMonth.getFullYear(), startMonth.getMonth() + m, 1);
  const daysInMonth = new Date(monthDate.getFullYear(), monthDate.getMonth() + 1, 0).getDate();

  for (const it of ITEMS) {
    // One PO every other month per item, alternating branches and suppliers deterministically.
    if (m % 2 !== fnv1a(it.item) % 2) continue;

    const key = `${it.item}-po-${m}`;
    const supplier = SUPPLIERS[Math.floor(rand(key + 's') * SUPPLIERS.length)];
    const branch = BRANCHES[Math.floor(rand(key + 'b') * BRANCHES.length)];
    const day = 1 + Math.floor(rand(key + 'd') * Math.max(1, daysInMonth - supplier.leadDays - 5));
    const orderDate = new Date(monthDate.getFullYear(), monthDate.getMonth(), day);
    const promised = new Date(orderDate);
    promised.setDate(promised.getDate() + supplier.leadDays);

    const costDrift = it.cost * (1 + 0.006 * m);
    const unitCost = Math.round(costDrift * (0.97 + rand(key + 'c') * 0.06) * 100) / 100;
    const freight = Math.round(unitCost * supplier.freightPct * 100) / 100;
    const duty = Math.round(unitCost * supplier.dutyPct * 100) / 100;
    const qty = Math.max(20, Math.round(50 + rand(key + 'q') * 400));

    // Recent orders (last ~3 weeks) are still open or in transit; everything older arrived,
    // usually close to promised, sometimes a few days late.
    const daysSinceOrder = Math.round((today - orderDate) / 86400000);
    let receivedDate = '';
    let qtyReceived = '';
    if (daysSinceOrder > supplier.leadDays + 21) {
      const lateDays = rand(key + 'l') < 0.75 ? 0 : Math.round(rand(key + 'l2') * 6);
      const received = new Date(promised);
      received.setDate(received.getDate() + lateDays);
      if (received <= today) {
        receivedDate = received.toISOString().slice(0, 10);
        qtyReceived = String(rand(key + 'short') < 0.06 ? Math.round(qty * 0.9) : qty);
      }
    } else if (daysSinceOrder > supplier.leadDays) {
      receivedDate = promised.toISOString().slice(0, 10);
      qtyReceived = String(qty);
    }
    // else: still in transit (order+promised in the future or too recent) - left blank, open.

    const po = `PO-${monthDate.getFullYear()}${String(monthDate.getMonth() + 1).padStart(2, '0')}-${String(poSeq++).padStart(4, '0')}`;
    rows.push([
      po,
      orderDate.toISOString().slice(0, 10),
      supplier.name,
      supplier.country,
      it.item,
      String(qty),
      unitCost.toFixed(2),
      freight.toFixed(2),
      duty.toFixed(2),
      branch,
      promised.toISOString().slice(0, 10),
      receivedDate,
      qtyReceived,
    ]);
  }
}

const csv = rows.map((r) => r.map((v) => (/[,"]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)).join(',')).join('\n') + '\n';
const out = path.join(__dirname, 'purchase-history-alt.csv');
fs.writeFileSync(out, csv);
console.log(`Wrote ${out}`);
console.log(`${rows.length - 1} POs, ${ITEMS.length} items, ${SUPPLIERS.length} suppliers, ${MONTHS} months`);
