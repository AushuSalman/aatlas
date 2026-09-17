/**
 * Generates the Hardin Supply Co sample: 26 months of sales, the purchase orders behind
 * them, a product master with costs and stock, competitor price observations, and the
 * supplier panel - a US plumbing/HVAC distributor with 17 SKUs, 9 branches, 8 customers
 * and 8 suppliers.
 *
 * Deterministic: every figure comes from an FNV-1a hash of a stable key, the same trick the
 * app's own Seeded helper uses. Re-running produces byte-identical files, so the integration
 * tests can sum these files and pin what the API reports.
 *
 * The calendar ends on ANCHOR (the last sales date). The sample loader moves every date
 * forward so the history ends on the day it is loaded; nothing here is ever in the future
 * after that shift, which is why received dates and observations are capped at ANCHOR.
 *
 * Writes to this directory and to src/main/resources/samples/ (the classpath copy the API
 * loads and serves).
 */
const fs = require('fs');
const path = require('path');

const OUT_DIRS = [__dirname, path.resolve(__dirname, '../../src/main/resources/samples')];

function hash(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
  return h >>> 0;
}
const rand = (k) => (hash(k) % 100000) / 100000;
const between = (k, lo, hi) => lo + rand(k) * (hi - lo);
const intBetween = (k, lo, hi) => Math.floor(between(k, lo, hi + 0.999));
const pick = (k, list) => list[Math.floor(rand(k) * list.length) % list.length];
const r2 = (n) => Math.round(n * 100) / 100;

// -- Calendar ------------------------------------------------------------------------------
// Jul 2024 .. Aug 2026 inclusive = 26 months, days 1-28. ANCHOR = SampleDates.ANCHOR.
const START = { y: 2024, m: 7 };
const MONTHS = 26;
const ANCHOR = '2026-08-28';

const DAY_MS = 86400000;
const toDate = (iso) => new Date(iso + 'T00:00:00Z');
const toIso = (d) => d.toISOString().slice(0, 10);
const addDays = (iso, n) => toIso(new Date(toDate(iso).getTime() + n * DAY_MS));
const daysBetween = (a, b) => Math.round((toDate(b).getTime() - toDate(a).getTime()) / DAY_MS);
const ymd = (y, m, d) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;

// -- The supplier panel (seed/suppliers.json, verbatim) -------------------------------------
const PANEL = [
  { name: 'Nordflow Valve Works',     country: 'Germany', lead: 7,  city: 'Düsseldorf',      website: 'nordflow-valve.de',     category: 'Valves',         otif: 88.61, priceIndex: 102.68, defect: 2.4, comms: 1.5, certs: ['ISO 9001', 'API 607', 'CE PED'],              holdsStock: false, contact: 'M. Brandt',    email: 'sales@nordflow-valve-works.example.com',     yearsTrading: 55 },
  { name: 'Cascade Copper Mills',     country: 'USA',     lead: 17, city: 'Spokane, WA',     website: 'cascadecopper.com',     category: 'Copper & brass', otif: 92.72, priceIndex: 106.55, defect: 2.3, comms: 5,   certs: ['ISO 9001', 'UL', 'ASTM B88', 'NSF/ANSI 61'],  holdsStock: true,  contact: 'R. Okonkwo',   email: 'sales@cascade-copper-mills.example.com',     yearsTrading: 9 },
  { name: 'Anhui Precision Fittings', country: 'China',   lead: 19, city: 'Hefei',           website: 'anhuiprecision.cn',     category: 'Fittings',       otif: 76.13, priceIndex: 89.37,  defect: 0.9, comms: 3.9, certs: ['ISO 9001', 'UL', 'ISO 14001'],               holdsStock: false, contact: 'L. Qiao',      email: 'sales@anhui-precision-fittings.example.com', yearsTrading: 41 },
  { name: 'Gulf States Polymer',      country: 'USA',     lead: 43, city: 'Baton Rouge, LA', website: 'gulfstatespolymer.com', category: 'Polymers',       otif: 92.13, priceIndex: 89.27,  defect: 1.3, comms: 1.5, certs: ['ISO 9001', 'UL', 'NSF/ANSI 14', 'ASTM F876'], holdsStock: false, contact: 'D. Vance',     email: 'sales@gulf-states-polymer.example.com',      yearsTrading: 42 },
  { name: 'Thermaline Systems',       country: 'Mexico',  lead: 33, city: 'Monterrey',       website: 'thermaline.mx',         category: 'Fittings',       otif: 86.52, priceIndex: 90.47,  defect: 2.2, comms: 4,   certs: ['ISO 9001', 'UL', 'ISO 14001'],               holdsStock: false, contact: 'S. Arreola',   email: 'sales@thermaline-systems.example.com',       yearsTrading: 48 },
  { name: 'Kotara Steel Products',    country: 'India',   lead: 35, city: 'Pune',            website: 'kotarasteel.in',        category: 'Steel',          otif: 84.13, priceIndex: 101.72, defect: 1.9, comms: 4.5, certs: ['ISO 9001', 'API 5L'],                        holdsStock: true,  contact: 'N. Raghavan',  email: 'sales@kotara-steel-products.example.com',    yearsTrading: 11 },
  { name: 'Larsen Brass & Bronze',    country: 'USA',     lead: 19, city: 'Erie, PA',        website: 'larsenbrass.com',       category: 'Copper & brass', otif: 81.9,  priceIndex: 98.58,  defect: 1.7, comms: 3.5, certs: ['ISO 9001', 'NSF/ANSI 61', 'ISO 14001'],       holdsStock: true,  contact: 'E. Halvorsen', email: 'sales@larsen-brass-and-bronze.example.com',  yearsTrading: 34 },
  { name: 'Pacific Rim Tooling',      country: 'Vietnam', lead: 7,  city: 'Hai Phong',       website: 'pacificrimtooling.vn',  category: 'Tooling',        otif: 82.8,  priceIndex: 91.86,  defect: 1,   comms: 3.7, certs: ['ISO 9001', 'CE', 'UL', 'ANSI B107'],          holdsStock: true,  contact: 'T. Nguyen',    email: 'sales@pacific-rim-tooling.example.com',      yearsTrading: 48 },
];
const S = Object.fromEntries(PANEL.map((s) => [s.name.split(' ')[0], s]));
const Nordflow = S.Nordflow, Cascade = S.Cascade, Anhui = S.Anhui, Gulf = S.Gulf, Thermaline = S.Thermaline,
  Kotara = S.Kotara, Larsen = S.Larsen, Pacific = S.Pacific;

// [inbound freight %, duty %, transit days] by origin, from seed/logistics.json.
const ORIGIN = { USA: [0, 0, 0], Mexico: [2.4, 0, 6], Germany: [5.1, 2.7, 21], China: [7.8, 12.5, 34], India: [8.4, 5.8, 38], Vietnam: [8.1, 6.2, 31] };

// -- The catalogue, as the sample dataset defines it --------------------------------------
// cost is what the item costs us; season says when it sells; sup[0] is the incumbent.
const PRODUCTS = [
  { id: 'HRD107744', d: '3/4 IN x 100FT SOFT COPPER COIL TYPE L',         cost: 281.40,  season: 'flat',   vol: 'low',  cat: 'Plumbing',      sub: 'Pipe & tube',   uom: 'coil',         com: 'copper',    sup: [Cascade, Larsen, Anhui] },
  { id: 'HRD118902', d: '1/2 IN COPPER TYPE L HARD TUBE 10FT',            cost: 19.10,   season: 'flat',   vol: 'high', cat: 'Plumbing',      sub: 'Pipe & tube',   uom: '10 ft length', com: 'copper',    sup: [Cascade, Larsen, Pacific] },
  { id: 'HRD248813', d: '1-1/4 IN GALVANIZED STEEL NIPPLE 6 IN',          cost: 1.72,    season: 'flat',   vol: 'bulk', cat: 'Plumbing',      sub: 'Fittings',      uom: 'each',         com: 'steel',     sup: [Kotara, Anhui, Thermaline] },
  { id: 'HRD290145', d: '2 IN PVC DWV SANITARY TEE HUB',                  cost: 4.60,    season: 'summer', vol: 'bulk', cat: 'Plumbing',      sub: 'Fittings',      uom: 'each',         com: 'pvc',       sup: [Gulf, Thermaline, Anhui] },
  { id: 'HRD304148', d: '3/4 IN CPVC 90 DEG ELBOW SOCKET',                cost: 6.92,    season: 'summer', vol: 'bulk', cat: 'Plumbing',      sub: 'Fittings',      uom: 'each',         com: 'pvc',       sup: [Gulf, Thermaline] },
  { id: 'HRD335590', d: 'CHROME LAVATORY FAUCET 4 IN CENTERSET 2-HANDLE', cost: 97.50,   season: 'flat',   vol: 'mid',  cat: 'Fixtures',      sub: 'Faucets',       uom: 'each',         com: 'brass',     sup: [Larsen, Anhui, Pacific] },
  { id: 'HRD450871', d: '40 GAL NATURAL GAS WATER HEATER 40 MBH',         cost: 498.20,  season: 'winter', vol: 'low',  cat: 'Water heating', sub: 'Tank heaters',  uom: 'each',         com: 'equipment', sup: [Thermaline, Kotara] },
  { id: 'HRD512066', d: '1/2 IN x 300FT PEX-B TUBING RED',                cost: 62.80,   season: 'flat',   vol: 'mid',  cat: 'Plumbing',      sub: 'PEX',           uom: 'coil',         com: 'pex',       sup: [Gulf, Thermaline] },
  { id: 'HRD661204', d: '1 IN PEX-A EXPANSION COUPLING BRASS',            cost: 2.35,    season: 'flat',   vol: 'bulk', cat: 'Plumbing',      sub: 'PEX',           uom: 'each',         com: 'pex',       sup: [Larsen, Anhui, Gulf] },
  { id: 'HRD772310', d: '3/4 IN BRASS BALL VALVE FULL PORT THREADED',     cost: 11.40,   season: 'flat',   vol: 'high', cat: 'Plumbing',      sub: 'Valves',        uom: 'each',         com: 'brass',     sup: [Nordflow, Larsen, Anhui] },
  { id: 'HRD874019', d: '4 IN CAST IRON NO-HUB COUPLING STAINLESS',       cost: 8.15,    season: 'flat',   vol: 'high', cat: 'Plumbing',      sub: 'Fittings',      uom: 'each',         com: 'iron',      sup: [Kotara, Anhui] },
  { id: 'HRD900001', d: '6 IN DUCTILE IRON MJ GATE VALVE (NEW SKU)',      cost: 612.00,  season: 'summer', vol: 'low',  cat: 'Plumbing',      sub: 'Valves',        uom: 'each',         com: 'iron',      sup: [Nordflow, Kotara] },
  { id: 'HRD900002', d: 'SMART THERMOSTAT WIFI 24V C-WIRE (NEW SKU)',     cost: 84.00,   season: 'winter', vol: 'mid',  cat: 'HVAC',          sub: 'Controls',      uom: 'each',         com: 'equipment', sup: [Pacific, Thermaline] },
  { id: 'HRD900003', d: '2 IN STAINLESS PRESS COUPLING 316L (NEW SKU)',   cost: 27.40,   season: 'flat',   vol: 'mid',  cat: 'Plumbing',      sub: 'Fittings',      uom: 'each',         com: 'steel',     sup: [Kotara, Nordflow, Anhui] },
  { id: 'HRD983377', d: '3 TON 14 SEER AC CONDENSING UNIT R-410A',        cost: 1455.00, season: 'summer', vol: 'low',  cat: 'HVAC',          sub: 'Cooling',       uom: 'each',         com: 'equipment', sup: [Thermaline, Pacific] },
  // Two SKUs the sample catalogue does not have: proves an import creates products.
  { id: 'HRD551200', d: '3/4 IN THERMOSTATIC MIXING VALVE LEAD FREE',     cost: 148.00,  season: 'winter', vol: 'mid',  cat: 'Water heating', sub: 'Mixing valves', uom: 'each',         com: 'brass',     sup: [Nordflow, Larsen] },
  { id: 'HRD551201', d: '50 GAL HEAT PUMP WATER HEATER 240V',             cost: 1180.00, season: 'winter', vol: 'low',  cat: 'Water heating', sub: 'Heat pump',     uom: 'each',         com: 'equipment', sup: [Thermaline, Kotara] },
];

const BRANCHES = ['100047', '100117', '100205', '100349', '100571', '100649', '100812', '100933', '100959'];

// The eight seed/customers.json names, verbatim, so every sales line lands on a tagged account.
const CUSTOMERS = [
  'Walk-in / no account', 'Halloran Mechanical', 'Ridgeline Plumbing Co.', 'Fairmont Health System',
  'Delta Ridge Industrial', 'Grayson HVAC Services', 'Westport Unified Schools', 'Sable Creek Refining',
];

// Volume band -> how many line items a branch writes for this item in a month.
const BAND = { bulk: [3, 9], high: [2, 6], mid: [1, 4], low: [0, 2] };
// Quantity per line, by band.
const QTY = { bulk: [60, 400], high: [20, 120], mid: [5, 40], low: [1, 8] };

/** 0.55 in the trough, 1.45 at the peak. Flat items ignore the month. */
function seasonFactor(season, month) {
  if (season === 'flat') return 1;
  const peak = season === 'summer' ? 7 : 1; // July / January
  const dist = Math.min(Math.abs(month - peak), 12 - Math.abs(month - peak));
  return 1.45 - (dist / 6) * 0.9;
}

/** Costs drift up over the window - copper and equipment faster than plastic. */
function costAt(p, monthIndex) {
  const drift = 1 + (monthIndex / MONTHS) * between(`drift:${p.id}`, 0.02, 0.16);
  return r2(p.cost * drift);
}

function monthOf(mi) {
  return { y: START.y + Math.floor((START.m - 1 + mi) / 12), m: ((START.m - 1 + mi) % 12) + 1 };
}

// -- Sales ---------------------------------------------------------------------------------
// Per (item, branch, month) a price factor pf moves every line's price and its quantity the
// other way (elasticity -1.3), so the elasticity estimate has a real signal to find.
const sales = [];
const units = {};        // units[item][mi][branch]
const salesByPair = {};  // salesByPair[item][branch] = [{date, qty, price}]

for (let mi = 0; mi < MONTHS; mi++) {
  const { y, m } = monthOf(mi);
  for (const p of PRODUCTS) {
    // The two new SKUs only start selling partway through - a catalogue grows.
    if (p.id.startsWith('HRD5512') && mi < 14) continue;
    if (p.id.startsWith('HRD9000') && mi < 6) continue;

    const sf = seasonFactor(p.season, m);
    for (const b of BRANCHES) {
      const key = `${p.id}:${b}:${y}-${m}`;
      const pf = between(key + ':pf', 0.90, 1.10);
      const [lo, hi] = BAND[p.vol];
      const lines = Math.max(0, Math.round(intBetween(key + ':n', lo, hi) * sf));
      for (let i = 0; i < lines; i++) {
        const k = `${key}:${i}`;
        const day = intBetween(k + ':d', 1, 28);
        const [qlo, qhi] = QTY[p.vol];
        const qty = Math.max(1, Math.round(intBetween(k + ':q', qlo, qhi) * Math.pow(pf, -1.3)));
        const cost = costAt(p, mi);
        // Margin varies by customer and branch; a few lines go out near cost.
        const margin = between(k + ':mg', 1.14, 1.62);
        const price = r2(cost * margin * pf);
        const cust = pick(k + ':c', CUSTOMERS);
        const date = ymd(y, m, day);
        sales.push([p.id, p.d, date, qty, price.toFixed(2), cost.toFixed(2), cust, b]);

        units[p.id] ??= {};
        units[p.id][mi] ??= {};
        units[p.id][mi][b] = (units[p.id][mi][b] || 0) + qty;
        salesByPair[p.id] ??= {};
        salesByPair[p.id][b] ??= [];
        salesByPair[p.id][b].push({ date, qty, price });
      }
    }
  }
}
sales.sort((a, b) => String(a[2]).localeCompare(String(b[2])));

// -- Purchases -----------------------------------------------------------------------------
// Per item and month with sales: 1-3 POs to the branches that sold most, mostly from the
// incumbent, landed at about the sales cost, promised at lead + transit, received a little
// early or late. Every PO has exactly one line.
const purchases = [];
for (const p of PRODUCTS) {
  for (let mi = 0; mi < MONTHS; mi++) {
    const byBranch = (units[p.id] || {})[mi];
    if (!byBranch) continue;
    const total = Object.values(byBranch).reduce((t, u) => t + u, 0);
    if (total <= 0) continue;
    const { y, m } = monthOf(mi);
    const k = `${p.id}:${y}-${m}`;
    const n = intBetween(k + ':po', 1, 3);
    const shipTo = BRANCHES.filter((b) => (byBranch[b] || 0) > 0)
      .sort((a, b) => (byBranch[b] - byBranch[a]) || BRANCHES.indexOf(a) - BRANCHES.indexOf(b))
      .slice(0, n);
    shipTo.forEach((b, i) => {
      const kk = `${k}:${i}`;
      const supplier = rand(kk + ':s') < 0.6 ? p.sup[0] : pick(kk + ':s2', p.sup.slice(1));
      const qty = Math.max(1, Math.round(byBranch[b] * between(kk + ':q', 0.85, 1.35)));
      const [f, d, transit] = ORIGIN[supplier.country];
      const landedTarget = costAt(p, mi) * between(kk + ':l', 0.97, 1.03);
      const exWorks = r2(landedTarget / (1 + (f + d) / 100));
      const freight = r2(exWorks * f / 100);
      const duty = r2(exWorks * d / 100);
      const landed = r2(exWorks + freight + duty);
      const orderDate = ymd(y, m, intBetween(kk + ':d', 1, 25));
      const promised = addDays(orderDate, supplier.lead + transit);
      const slip = rand(kk + ':ot') < 0.72 ? -intBetween(kk + ':e', 0, 3) : intBetween(kk + ':lt', 1, 12);
      const receivedRaw = addDays(promised, slip);
      const received = receivedRaw > ANCHOR ? '' : receivedRaw;
      const qtyReceived = received === '' ? ''
        : (rand(kk + ':short') < 0.06 ? Math.round(qty * between(kk + ':sq', 0.85, 0.98)) : qty);
      purchases.push({
        orderDate, supplier, item: p, qty, exWorks, freight, duty, landed, shipTo: b, promised, received, qtyReceived,
      });
    });
  }
}
purchases.sort((a, b) => a.orderDate.localeCompare(b.orderDate));
purchases.forEach((po, i) => {
  po.number = `PO-${po.orderDate.slice(0, 4)}${po.orderDate.slice(5, 7)}-${String(i + 1).padStart(4, '0')}`;
});

// The ledger's own status rule, evaluated at ANCHOR: received; in transit once ~35% of the
// promised lead has elapsed; else open. Printed so the tests know at least one row is open.
function statusAt(po, today) {
  if (po.received !== '') return 'received';
  const promisedDays = daysBetween(po.orderDate, po.promised);
  return addDays(po.orderDate, Math.floor(promisedDays * 0.35)) <= today ? 'in-transit' : 'open';
}

// -- Products (master + stock) -------------------------------------------------------------
function unitsInWindow(item, branch, from, to) {
  return ((salesByPair[item] || {})[branch] || [])
    .filter((s) => s.date >= from && s.date <= to)
    .reduce((t, s) => t + s.qty, 0);
}
function weeklyUnits(item, branch) {
  const w13 = unitsInWindow(item, branch, addDays(ANCHOR, -90), ANCHOR);
  if (w13 > 0) return w13 / 13;
  return unitsInWindow(item, branch, addDays(ANCHOR, -364), ANCHOR) / 52;
}
/** Quantity-weighted average net price over the trailing 90 days (else 12 months). */
function listPrice(item) {
  const all = Object.values(salesByPair[item] || {}).flat();
  for (const from of [addDays(ANCHOR, -89), addDays(ANCHOR, -364)]) {
    const rows = all.filter((s) => s.date >= from && s.date <= ANCHOR);
    const q = rows.reduce((t, s) => t + s.qty, 0);
    if (q > 0) return rows.reduce((t, s) => t + s.qty * s.price, 0) / q;
  }
  return 0;
}

const products = [];
for (const p of PRODUCTS) {
  const inc = p.sup[0];
  const [f, d, transit] = ORIGIN[inc.country];
  const unitCost = costAt(p, MONTHS - 1);
  const supplierCost = r2(unitCost / (1 + (f + d) / 100));
  // List Price deliberately blank: the demo shows prices derived from sales, and the
  // "Set your prices" wizard stays demonstrable.
  products.push([p.id, p.d, p.cat, p.sub, p.com, '', unitCost.toFixed(2), '', '', inc.name, supplierCost.toFixed(2), inc.lead + transit, p.uom]);
}
for (const p of PRODUCTS) {
  for (const b of BRANCHES) {
    const onHand = Math.round(weeklyUnits(p.id, b) * between(`${p.id}:${b}:oh`, 6, 14));
    products.push([p.id, p.d, '', '', '', '', '', onHand, b, '', '', '', '']);
  }
}

// -- Competitor prices -----------------------------------------------------------------------
const COMPETITORS = ['Northline Supply', 'Brightwell Distribution', 'Summit Pipe & Supply', 'Larkspur Trade Supply', 'Meridian Plumbing Wholesale'];
const REGIONS = ['south', 'west', 'north', 'east'];
const competitorPrices = [];
for (const p of PRODUCTS) {
  const n = intBetween(p.id + ':cn', 2, 3);
  const chosen = [...COMPETITORS].sort((a, b) => rand(`${p.id}:co:${a}`) - rand(`${p.id}:co:${b}`)).slice(0, n);
  const lp = listPrice(p.id);
  for (const c of chosen) {
    const m = intBetween(`${p.id}:${c}:m`, 1, 2);
    const regions = [...REGIONS].sort((a, b) => rand(`${p.id}:${c}:r:${a}`) - rand(`${p.id}:${c}:r:${b}`)).slice(0, m);
    for (const region of regions) {
      const kk = `${p.id}:${c}:${region}`;
      const price = r2(lp * between(kk + ':p', 0.88, 1.12));
      const observed = addDays(ANCHOR, -intBetween(kk + ':d', 2, 60));
      competitorPrices.push([p.id, c, price.toFixed(2), 'USD', region, observed, '']);
    }
  }
}

// -- Suppliers (the panel, in a real ERP export's column names) ----------------------------
const supplierRows = PANEL.map((s) => [
  s.name, s.country, s.city, s.website, s.category, s.lead, s.otif, s.priceIndex, s.defect, s.comms,
  s.certs.join('; '), s.holdsStock ? 'yes' : 'no', s.contact, s.email, 2026 - s.yearsTrading,
]);

// -- Write -----------------------------------------------------------------------------------
function cell(v) {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}
function csv(header, rows) {
  return [header, ...rows.map((r) => r.map(cell).join(','))].join('\n') + '\n';
}
const FILES = {
  'sales-history.csv': csv('Item No,Item Description,Invoice Date,Qty Shipped,Net Price,Unit Cost,Bill To,Whse', sales),
  'purchase-history.csv': csv(
    'PO Number,Order Date,Supplier,Supplier Country,Item No,Item Description,Qty Ordered,Unit Cost,Freight,Duty,Landed Cost,Currency,Ship To,Promised Date,Received Date,Qty Received',
    purchases.map((po) => [
      po.number, po.orderDate, po.supplier.name, po.supplier.country, po.item.id, po.item.d, po.qty,
      po.exWorks.toFixed(2), po.freight.toFixed(2), po.duty.toFixed(2), po.landed.toFixed(2), 'USD', po.shipTo,
      po.promised, po.received, po.qtyReceived,
    ])),
  'products.csv': csv('Item No,Description,Category,Subcategory,Commodity,List Price,Unit Cost,On Hand,Branch,Supplier,Supplier Cost,Lead Time,UOM', products),
  'competitor-prices.csv': csv('Item No,Competitor,Price,Currency,Region/Branch,Observed Date,Source URL', competitorPrices),
  'suppliers.csv': csv(
    'Vendor Name,Country,City,Website,Category,Lead Time (days),On Time %,Price vs Market,Reject Rate %,Comms,Certifications,Ex Stock,Primary Contact,Email,Established',
    supplierRows),
};
for (const dir of OUT_DIRS) {
  fs.mkdirSync(dir, { recursive: true });
  for (const [name, text] of Object.entries(FILES)) fs.writeFileSync(path.join(dir, name), text);
}

// -- Totals (the oracle the integration tests pin) --------------------------------------------
const money = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const revenue = sales.reduce((t, r) => t + r[3] * Number(r[4]), 0);
const spend = purchases.reduce((t, po) => t + po.landed * po.qty, 0);
const statuses = purchases.reduce((acc, po) => { const s = statusAt(po, ANCHOR); acc[s] = (acc[s] || 0) + 1; return acc; }, {});
const firstSale = sales[0][2];
const lastSale = sales[sales.length - 1][2];

console.log(`sales-history.csv     : ${sales.length} rows, ${PRODUCTS.length} items, ${BRANCHES.length} branches, ${MONTHS} months (${firstSale} .. ${lastSale})`);
console.log(`purchase-history.csv  : ${purchases.length} rows (received ${statuses.received || 0}, in-transit ${statuses['in-transit'] || 0}, open ${statuses.open || 0})`);
console.log(`products.csv          : ${products.length} rows (${PRODUCTS.length} items + ${PRODUCTS.length * BRANCHES.length} branch stock rows)`);
console.log(`competitor-prices.csv : ${competitorPrices.length} rows`);
console.log(`suppliers.csv         : ${PANEL.length} suppliers`);
console.log(`total revenue         : $${money(revenue)}`);
console.log(`total PO spend        : $${money(spend)} (landed x qty)`);
console.log('incumbent per item    : share of landed spend, all 26 months | trailing 12 months to ' + ANCHOR);
const from12 = addDays(ANCHOR, -364);
for (const p of PRODUCTS) {
  const share = (rows) => {
    const bySup = {};
    let total = 0;
    for (const po of rows) { const v = po.landed * po.qty; bySup[po.supplier.name] = (bySup[po.supplier.name] || 0) + v; total += v; }
    const top = Object.entries(bySup).sort((a, b) => b[1] - a[1])[0];
    return top ? `${top[0]} ${(top[1] / total * 100).toFixed(1)}%` : 'none';
  };
  const all = purchases.filter((po) => po.item.id === p.id);
  const w12 = all.filter((po) => po.orderDate >= from12 && po.orderDate <= ANCHOR);
  console.log(`  ${p.id}  ${share(all)} | ${share(w12)}`);
}
