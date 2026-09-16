/**
 * Generates a realistic 26-month sales history and a supplier list for Hardin Supply Co,
 * a US plumbing/HVAC distributor.
 *
 * Deterministic: every figure comes from an FNV-1a hash of a stable key, the same trick the
 * app's own Seeded helper uses. Re-running produces byte-identical files, so a re-import
 * compares cleanly against the last one.
 */
const fs = require('fs');
const SP = __dirname;

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

// -- The catalogue, as the sample dataset defines it --------------------------------------
// baseCost is what the item costs us; season says when it sells.
const PRODUCTS = [
  { id: 'HRD107744', d: '3/4 IN x 100FT SOFT COPPER COIL TYPE L',           cost: 281.40, season: 'flat',   vol: 'low'  },
  { id: 'HRD118902', d: '1/2 IN COPPER TYPE L HARD TUBE 10FT',              cost: 19.10,  season: 'flat',   vol: 'high' },
  { id: 'HRD248813', d: '1-1/4 IN GALVANIZED STEEL NIPPLE 6 IN',            cost: 1.72,   season: 'flat',   vol: 'bulk' },
  { id: 'HRD290145', d: '2 IN PVC DWV SANITARY TEE HUB',                    cost: 4.60,   season: 'summer', vol: 'bulk' },
  { id: 'HRD304148', d: '3/4 IN CPVC 90 DEG ELBOW SOCKET',                  cost: 6.92,   season: 'summer', vol: 'bulk' },
  { id: 'HRD335590', d: 'CHROME LAVATORY FAUCET 4 IN CENTERSET 2-HANDLE',   cost: 97.50,  season: 'flat',   vol: 'mid'  },
  { id: 'HRD450871', d: '40 GAL NATURAL GAS WATER HEATER 40 MBH',           cost: 498.20, season: 'winter', vol: 'low'  },
  { id: 'HRD512066', d: '1/2 IN x 300FT PEX-B TUBING RED',                  cost: 62.80,  season: 'flat',   vol: 'mid'  },
  { id: 'HRD661204', d: '1 IN PEX-A EXPANSION COUPLING BRASS',              cost: 2.35,   season: 'flat',   vol: 'bulk' },
  { id: 'HRD772310', d: '3/4 IN BRASS BALL VALVE FULL PORT THREADED',       cost: 11.40,  season: 'flat',   vol: 'high' },
  { id: 'HRD874019', d: '4 IN CAST IRON NO-HUB COUPLING STAINLESS',         cost: 8.15,   season: 'flat',   vol: 'high' },
  { id: 'HRD900001', d: '6 IN DUCTILE IRON MJ GATE VALVE (NEW SKU)',        cost: 612.00, season: 'summer', vol: 'low'  },
  { id: 'HRD900002', d: 'SMART THERMOSTAT WIFI 24V C-WIRE (NEW SKU)',       cost: 84.00,  season: 'winter', vol: 'mid'  },
  { id: 'HRD900003', d: '2 IN STAINLESS PRESS COUPLING 316L (NEW SKU)',     cost: 27.40,  season: 'flat',   vol: 'mid'  },
  { id: 'HRD983377', d: '3 TON 14 SEER AC CONDENSING UNIT R-410A',          cost: 1455.00, season: 'summer', vol: 'low' },
  // Two SKUs the sample catalogue does not have: proves an import creates products.
  { id: 'HRD551200', d: '3/4 IN THERMOSTATIC MIXING VALVE LEAD FREE',       cost: 148.00, season: 'winter', vol: 'mid'  },
  { id: 'HRD551201', d: '50 GAL HEAT PUMP WATER HEATER 240V',               cost: 1180.00, season: 'winter', vol: 'low' },
];

const BRANCHES = ['100047', '100117', '100205', '100349', '100571', '100649', '100812', '100933', '100959'];

const CUSTOMERS = [
  'Halloran Mechanical', 'Ridgeline Plumbing Co.', 'Grayson HVAC Services', 'Delta Ridge Industrial',
  'Fairmont Health System', 'Westport Unified Schools', 'Tidewater Mechanical', 'Sable Creek Refining',
  'Northgate Property Group', 'Cortland Fire Protection', 'Vance & Sons Plumbing', 'Orion Municipal Works',
  'Brightwater Facilities', 'Kestrel Construction LLC', 'Walk-in',
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
  const drift = 1 + (monthIndex / 26) * between(`drift:${p.id}`, 0.02, 0.16);
  return r2(p.cost * drift);
}

const rows = [];
const START = { y: 2024, m: 8 }; // August 2024 .. September 2026 inclusive = 26 months

for (let mi = 0; mi < 26; mi++) {
  const y = START.y + Math.floor((START.m - 1 + mi) / 12);
  const m = ((START.m - 1 + mi) % 12) + 1;

  for (const p of PRODUCTS) {
    // The two new SKUs only start selling partway through - a catalogue grows.
    if (p.id.startsWith('HRD5512') && mi < 14) continue;
    if (p.id.startsWith('HRD9000') && mi < 6) continue;

    const sf = seasonFactor(p.season, m);
    for (const b of BRANCHES) {
      const key = `${p.id}:${b}:${y}-${m}`;
      const [lo, hi] = BAND[p.vol];
      const lines = Math.max(0, Math.round(intBetween(key + ':n', lo, hi) * sf));
      for (let i = 0; i < lines; i++) {
        const k = `${key}:${i}`;
        const day = intBetween(k + ':d', 1, 28);
        const [qlo, qhi] = QTY[p.vol];
        const qty = intBetween(k + ':q', qlo, qhi);
        const cost = costAt(p, mi);
        // Margin varies by customer and branch; a few lines go out near cost.
        const margin = between(k + ':mg', 1.14, 1.62);
        const price = r2(cost * margin);
        const cust = pick(k + ':c', CUSTOMERS);
        const date = `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
        rows.push([p.id, p.d, date, qty, price.toFixed(2), cost.toFixed(2), cust, b]);
      }
    }
  }
}

rows.sort((a, b) => String(a[2]).localeCompare(String(b[2])));

const header = 'Item No,Item Description,Invoice Date,Qty Shipped,Net Price,Unit Cost,Bill To,Whse';
const csv = [header, ...rows.map((r) => r.join(','))].join('\n') + '\n';
fs.writeFileSync(SP + '/sales-history.csv', csv);

// -- Suppliers -----------------------------------------------------------------------------
const SUPPLIERS = [
  ['Atlas Copper Works',        'USA',     'Cleveland OH',    'Copper & brass', 12, 94.2, 99,  0.7, 4.5, 'ISO 9001; ASTM B88',      'yes', 'Dana Whitfield',  1991],
  ['Keystone Valve & Fitting',  'USA',     'Pittsburgh PA',   'Valves',         9,  96.8, 103, 0.4, 4.5, 'ISO 9001; API 6D',        'yes', 'Marcus Vaughn',   1978],
  ['Rio Bravo Polymers',        'Mexico',  'Monterrey',       'Polymers',       14, 89.5, 92,  1.9, 3.5, 'ISO 9001',                'yes', 'Elena Cruz',      2004],
  ['Thames Steel Supply',       'UK',      'Sheffield',       'Steel',          21, 91.3, 101, 1.1, 4.0, 'ISO 9001; ISO 14001',     'no',  'Owen Hartley',    1969],
  ['Shenzhen Precision Tools',  'China',   'Shenzhen',        'Tooling',        38, 86.1, 84,  2.8, 3.0, 'ISO 9001',                'no',  'Li Wei',          2008],
  ['Ganges Fittings Ltd',       'India',   'Pune',            'Fittings',       31, 88.4, 88,  2.2, 3.0, 'ISO 9001; NSF/ANSI 61',   'no',  'Ravi Menon',      2001],
  ['Mekong Brass Industries',   'Vietnam', 'Ho Chi Minh City', 'Copper & brass', 34, 87.6, 86, 2.4, 2.5, 'ISO 9001',                'no',  'Tran Minh',       2012],
  ['Laurentide Iron Foundry',   'Canada',  'Hamilton ON',     'Steel',          16, 93.7, 97,  0.9, 4.0, 'ISO 9001; ASTM A888',     'yes', 'Claire Dubois',   1954],
  ['Rhine Valve Technik',       'Germany', 'Duisburg',        'Valves',         18, 97.1, 108, 0.3, 5.0, 'ISO 9001; PED 2014/68/EU', 'yes', 'Stefan Keller',  1963],
  ['Gulf Coast Polymer',        'USA',     'Houston TX',      'Polymers',       7,  95.4, 100, 0.8, 4.5, 'ISO 9001; NSF/ANSI 61',   'yes', 'Bea Trujillo',    1996],
];

const supHeader = 'Vendor Name,Country,City,Website,Commodity,Lead Time (days),On Time %,Price vs Market,Reject Rate %,Comms,Certifications,Ex Stock,Primary Contact,Email,Phone,Established';
const supRows = SUPPLIERS.map((s) => {
  const [name, country, city, cat, lead, otif, px, defect, comms, certs, stock, contact, est] = s;
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '');
  const first = contact.split(' ')[0].toLowerCase();
  const web = `${slug}.example.com`;
  const email = `${first}@${slug}.example.com`;
  const phone = '+1 555 0' + String(100 + hash(name) % 800);
  return [name, country, city, web, cat, lead, otif, px, defect, comms, `"${certs}"`, stock, contact, email, phone, est].join(',');
});
fs.writeFileSync(SP + '/suppliers.csv', [supHeader, ...supRows].join('\n') + '\n');

console.log(`sales-history.csv : ${rows.length} rows, ${PRODUCTS.length} items, ${BRANCHES.length} branches, 26 months`);
console.log(`suppliers.csv     : ${SUPPLIERS.length} suppliers`);
const spend = rows.reduce((t, r) => t + Number(r[3]) * Number(r[4]), 0);
console.log(`total revenue     : $${Math.round(spend).toLocaleString()}`);
