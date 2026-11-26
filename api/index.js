import fs from "node:fs";
import pg from "pg";

const { Pool } = pg;

const CAR_CAPACITY = 30;
const BIKE_CAPACITY = 20;
const DEFAULT_CAR_RATE = 30;
const DEFAULT_BIKE_RATE = 15;
const TMP_STORE_PATH = "/tmp/decathlon-smart-parking.json";

const connectionString =
  process.env.POSTGRES_URL ||
  process.env.DATABASE_URL ||
  process.env.POSTGRES_PRISMA_URL ||
  process.env.POSTGRES_URL_NON_POOLING ||
  "";

const supabaseUrl = (process.env.SUPABASE_URL || "").replace(/\/+$/, "");
const supabaseSecretKey =
  process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || "";
const supabasePublishableKey =
  process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY || "";
const supabaseJwksUrl = process.env.SUPABASE_JWKS_URL || "";

let pool = null;
let schemaReadyPromise = null;
let memoryStore = null;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function roundMoney(value) {
  return Math.round(Number(value) * 100) / 100;
}

function pickFreeSlot(takenSlots, vehicleType) {
  const capacity = vehicleType === "CAR" ? CAR_CAPACITY : BIKE_CAPACITY;
  const prefix = vehicleType === "CAR" ? "C" : "B";
  for (let i = 1; i <= capacity; i++) {
    const candidate = prefix + String(i).padStart(2, "0");
    if (!takenSlots.has(candidate)) {
      return candidate;
    }
  }
  return null;
}

function computeBill(entryTimeIso, ratePerHour) {
  const now = new Date();
  const minutes = Math.max(
    1,
    Math.floor((now.getTime() - new Date(entryTimeIso).getTime()) / 60000)
  );
  const billableHours = Math.max(1, Math.ceil(minutes / 60));
  const amount = roundMoney(Number(ratePerHour) * billableHours);
  return { now, minutes, billableHours, amount };
}

function buildDashboardPayload({
  parkedCars,
  parkedBikes,
  carRatePerHour,
  bikeRatePerHour,
  totalCheckouts,
  totalRevenue,
  storageMode
}) {
  return {
    parkedCars,
    parkedBikes,
    totalParked: parkedCars + parkedBikes,
    availableCarSlots: CAR_CAPACITY - parkedCars,
    availableBikeSlots: BIKE_CAPACITY - parkedBikes,
    carCapacity: CAR_CAPACITY,
    bikeCapacity: BIKE_CAPACITY,
    carRatePerHour: Number(carRatePerHour),
    bikeRatePerHour: Number(bikeRatePerHour),
    totalCheckouts: Number(totalCheckouts || 0),
    totalRevenue: roundMoney(totalRevenue || 0),
    storageMode
  };
}

function mapDbRecord(row) {
  return {
    id: Number(row.id),
    vehicleType: row.vehicle_type,
    brand: row.brand,
    model: row.model,
    numberPlate: row.number_plate,
    slotNumber: row.slot_number,
    entryTime: new Date(row.entry_time).toISOString(),
    exitTime: row.exit_time ? new Date(row.exit_time).toISOString() : null,
    durationMinutes: Number(row.duration_minutes || 0),
    amount: row.amount !== null && row.amount !== undefined ? Number(row.amount) : null,
    status: row.status
  };
}

function validateEntryPayload(body) {
  const vehicleType = String(body?.vehicleType ?? "").trim().toUpperCase();
  const brand = String(body?.brand ?? "").trim();
  const model = String(body?.model ?? "").trim();
  const numberPlate = String(body?.numberPlate ?? "").trim().toUpperCase();

  if (vehicleType !== "CAR" && vehicleType !== "BIKE") {
    throw new HttpError(400, "Vehicle type must be CAR or BIKE.");
  }
  if (!brand) throw new HttpError(400, "brand: must not be blank");
  if (!model) throw new HttpError(400, "model: must not be blank");
  if (!numberPlate) throw new HttpError(400, "numberPlate: must not be blank");
  if (numberPlate.length > 20) {
    throw new HttpError(400, "numberPlate: size must be between 0 and 20");
  }
  return { vehicleType, brand, model, numberPlate };
}

function validateRatesPayload(body) {
  const car = Number(body?.carRatePerHour);
  const bike = Number(body?.bikeRatePerHour);
  if (!Number.isFinite(car) || !Number.isFinite(bike) || car < 0 || bike < 0) {
    throw new HttpError(400, "Rates must be zero or greater.");
  }
  return {
    carRatePerHour: roundMoney(car),
    bikeRatePerHour: roundMoney(bike)
  };
}

async function readJsonBody(req) {
  if (req.body && typeof req.body === "object" && !Buffer.isBuffer(req.body)) {
    return req.body;
  }
  if (typeof req.body === "string") {
    return req.body ? JSON.parse(req.body) : {};
  }
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  const raw = Buffer.concat(chunks).toString("utf8").trim();
  return raw ? JSON.parse(raw) : {};
}

function sendJson(res, status, data) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.end(JSON.stringify(data));
}

function resolveApiPath(req) {
  const urlObj = new URL(req.url || "/", "http://localhost");
  const paramPath = urlObj.searchParams.get("path");
  if (urlObj.pathname === "/api/index" && paramPath !== null) {
    return "/api/" + paramPath.replace(/^\/+/, "");
  }
  return urlObj.pathname;
}

function activeStorageModeLabel() {
  if (connectionString) {
    return connectionString.includes("supabase") || supabaseUrl
      ? "supabase-postgres"
      : "postgresql";
  }
  if (supabaseUrl && supabaseSecretKey) {
    return "supabase";
  }
  return "ephemeral";
}

function getPool() {
  if (!connectionString) return null;
  if (!pool) {
    const isLocal =
      connectionString.includes("localhost") ||
      connectionString.includes("127.0.0.1") ||
      connectionString.includes("sslmode=disable");
    pool = new Pool({
      connectionString,
      max: 5,
      ssl: isLocal ? false : { rejectUnauthorized: false }
    });
  }
  return pool;
}

async function ensureSchema(dbPool) {
  if (!schemaReadyPromise) {
    schemaReadyPromise = (async () => {
      await dbPool.query(`
        CREATE TABLE IF NOT EXISTS public.parking_records (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          vehicle_type VARCHAR(16) NOT NULL,
          brand VARCHAR(100) NOT NULL,
          model VARCHAR(100) NOT NULL,
          number_plate VARCHAR(20) NOT NULL,
          slot_number VARCHAR(10) NOT NULL,
          entry_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          exit_time TIMESTAMPTZ,
          duration_minutes BIGINT NOT NULL DEFAULT 0,
          amount NUMERIC(10, 2),
          status VARCHAR(16) NOT NULL DEFAULT 'PARKED'
        );
      `);
      await dbPool.query(`
        CREATE INDEX IF NOT EXISTS idx_parking_records_status
        ON public.parking_records (status);
      `);
      await dbPool.query(`
        CREATE TABLE IF NOT EXISTS public.parking_settings (
          id BIGINT PRIMARY KEY,
          car_rate_per_hour NUMERIC(10, 2) NOT NULL DEFAULT 30.00,
          bike_rate_per_hour NUMERIC(10, 2) NOT NULL DEFAULT 15.00,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
      `);
      await dbPool.query(`
        INSERT INTO public.parking_settings (id, car_rate_per_hour, bike_rate_per_hour)
        VALUES (1, 30.00, 15.00)
        ON CONFLICT (id) DO NOTHING;
      `);
    })().catch((err) => {
      schemaReadyPromise = null;
      throw err;
    });
  }
  await schemaReadyPromise;
}

async function handlePostgres(method, pathname, req) {
  const dbPool = getPool();
  await ensureSchema(dbPool);
  const mode = activeStorageModeLabel();

  if (method === "GET" && pathname === "/api/health") {
    await dbPool.query("SELECT 1");
    return { status: 200, body: { status: "UP", storageMode: mode } };
  }

  if (method === "GET" && pathname === "/api/settings/rates") {
    const { rows } = await dbPool.query(
      "SELECT car_rate_per_hour, bike_rate_per_hour FROM public.parking_settings WHERE id = 1"
    );
    const row = rows[0] || { car_rate_per_hour: DEFAULT_CAR_RATE, bike_rate_per_hour: DEFAULT_BIKE_RATE };
    return {
      status: 200,
      body: {
        carRatePerHour: Number(row.car_rate_per_hour),
        bikeRatePerHour: Number(row.bike_rate_per_hour)
      }
    };
  }

  if (method === "PUT" && pathname === "/api/settings/rates") {
    const body = await readJsonBody(req);
    const { carRatePerHour, bikeRatePerHour } = validateRatesPayload(body);
    await dbPool.query(
      `INSERT INTO public.parking_settings (id, car_rate_per_hour, bike_rate_per_hour, updated_at)
       VALUES (1, $1, $2, NOW())
       ON CONFLICT (id) DO UPDATE
       SET car_rate_per_hour = EXCLUDED.car_rate_per_hour,
           bike_rate_per_hour = EXCLUDED.bike_rate_per_hour,
           updated_at = NOW()`,
      [carRatePerHour, bikeRatePerHour]
    );
    return { status: 200, body: { carRatePerHour, bikeRatePerHour } };
  }

  if (method === "GET" && pathname === "/api/dashboard") {
    const [countsRes, settingsRes, revenueRes] = await Promise.all([
      dbPool.query(
        `SELECT vehicle_type, COUNT(*)::int AS cnt
         FROM public.parking_records
         WHERE status = 'PARKED'
         GROUP BY vehicle_type`
      ),
      dbPool.query(
        "SELECT car_rate_per_hour, bike_rate_per_hour FROM public.parking_settings WHERE id = 1"
      ),
      dbPool.query(
        `SELECT COUNT(*)::int AS total_checkouts, COALESCE(SUM(amount), 0)::numeric(12, 2) AS total_revenue
         FROM public.parking_records
         WHERE status = 'PAID'`
      )
    ]);

    let parkedCars = 0;
    let parkedBikes = 0;
    for (const row of countsRes.rows) {
      if (row.vehicle_type === "CAR") parkedCars = row.cnt;
      if (row.vehicle_type === "BIKE") parkedBikes = row.cnt;
    }
    const settings = settingsRes.rows[0] || {
      car_rate_per_hour: DEFAULT_CAR_RATE,
      bike_rate_per_hour: DEFAULT_BIKE_RATE
    };
    const rev = revenueRes.rows[0] || { total_checkouts: 0, total_revenue: 0 };

    return {
      status: 200,
      body: buildDashboardPayload({
        parkedCars,
        parkedBikes,
        carRatePerHour: settings.car_rate_per_hour,
        bikeRatePerHour: settings.bike_rate_per_hour,
        totalCheckouts: rev.total_checkouts,
        totalRevenue: rev.total_revenue,
        storageMode: mode
      })
    };
  }

  if (method === "GET" && pathname === "/api/parking/active") {
    const { rows } = await dbPool.query(
      `SELECT * FROM public.parking_records
       WHERE status = 'PARKED'
       ORDER BY entry_time DESC`
    );
    return { status: 200, body: rows.map(mapDbRecord) };
  }

  if (method === "GET" && pathname === "/api/parking/history") {
    const { rows } = await dbPool.query(
      `SELECT * FROM public.parking_records
       WHERE status = 'PAID'
       ORDER BY exit_time DESC NULLS LAST, id DESC
       LIMIT 50`
    );
    return { status: 200, body: rows.map(mapDbRecord) };
  }

  if (method === "POST" && pathname === "/api/parking/entry") {
    const body = await readJsonBody(req);
    const { vehicleType, brand, model, numberPlate } = validateEntryPayload(body);

    const client = await dbPool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(742091)");

      const dup = await client.query(
        `SELECT id FROM public.parking_records
         WHERE LOWER(number_plate) = LOWER($1) AND status = 'PARKED'
         LIMIT 1`,
        [numberPlate]
      );
      if (dup.rowCount > 0) {
        throw new HttpError(400, "This number plate already has an active parking session.");
      }

      const activeSlotsRes = await client.query(
        `SELECT slot_number FROM public.parking_records
         WHERE status = 'PARKED' AND vehicle_type = $1`,
        [vehicleType]
      );
      const slot = pickFreeSlot(
        new Set(activeSlotsRes.rows.map((r) => r.slot_number)),
        vehicleType
      );
      if (!slot) {
        throw new HttpError(400, `No available ${vehicleType.toLowerCase()} spaces.`);
      }

      const inserted = await client.query(
        `INSERT INTO public.parking_records (vehicle_type, brand, model, number_plate, slot_number, entry_time, status)
         VALUES ($1, $2, $3, $4, $5, NOW(), 'PARKED')
         RETURNING *`,
        [vehicleType, brand, model, numberPlate, slot]
      );
      await client.query("COMMIT");
      return { status: 201, body: mapDbRecord(inserted.rows[0]) };
    } catch (err) {
      await client.query("ROLLBACK").catch(() => {});
      throw err;
    } finally {
      client.release();
    }
  }

  const checkoutMatch = pathname.match(/^\/api\/parking\/(\d+)\/checkout$/);
  if (method === "POST" && checkoutMatch) {
    const id = Number(checkoutMatch[1]);
    const client = await dbPool.connect();
    try {
      await client.query("BEGIN");
      const recRes = await client.query(
        "SELECT * FROM public.parking_records WHERE id = $1 FOR UPDATE",
        [id]
      );
      if (recRes.rowCount === 0) {
        throw new HttpError(404, "Parking record not found.");
      }
      const existing = recRes.rows[0];
      if (existing.status !== "PARKED") {
        throw new HttpError(400, "This vehicle has already checked out.");
      }

      const settingsRes = await client.query(
        "SELECT car_rate_per_hour, bike_rate_per_hour FROM public.parking_settings WHERE id = 1"
      );
      const settings = settingsRes.rows[0] || {
        car_rate_per_hour: DEFAULT_CAR_RATE,
        bike_rate_per_hour: DEFAULT_BIKE_RATE
      };
      const rate =
        existing.vehicle_type === "CAR"
          ? Number(settings.car_rate_per_hour)
          : Number(settings.bike_rate_per_hour);

      const { now, minutes, billableHours, amount } = computeBill(existing.entry_time, rate);
      const updated = await client.query(
        `UPDATE public.parking_records
         SET exit_time = $1, duration_minutes = $2, amount = $3, status = 'PAID'
         WHERE id = $4
         RETURNING *`,
        [now.toISOString(), minutes, amount, id]
      );
      await client.query("COMMIT");
      return {
        status: 200,
        body: {
          record: mapDbRecord(updated.rows[0]),
          billableHours,
          message: "Checkout completed and slot released."
        }
      };
    } catch (err) {
      await client.query("ROLLBACK").catch(() => {});
      throw err;
    } finally {
      client.release();
    }
  }

  throw new HttpError(404, "API route not found.");
}

async function supabaseRest(restPath, { method = "GET", body, prefer } = {}) {
  const headers = {
    apikey: supabaseSecretKey,
    "User-Agent": "decathlon-parking-server/1.0",
    Accept: "application/json"
  };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (prefer) {
    headers["Prefer"] = prefer;
  }

  const resp = await fetch(`${supabaseUrl}/rest/v1/${restPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined
  });

  const text = await resp.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { message: text };
    }
  }

  if (!resp.ok) {
    const code = data?.code || "";
    const msg = data?.message || data?.error || `Supabase request failed (${resp.status})`;
    const err = new Error(msg);
    err.httpStatus = resp.status;
    err.pgCode = code;
    err.isMissingTable =
      code === "PGRST205" ||
      code === "42P01" ||
      (resp.status === 404 && /schema cache|does not exist/i.test(msg));
    throw err;
  }

  return data;
}

async function getOrCreateSupabaseSettings() {
  const rows = await supabaseRest("parking_settings?id=eq.1&select=*");
  if (rows && rows.length > 0) {
    return {
      carRatePerHour: Number(rows[0].car_rate_per_hour),
      bikeRatePerHour: Number(rows[0].bike_rate_per_hour)
    };
  }
  const inserted = await supabaseRest("parking_settings?on_conflict=id", {
    method: "POST",
    prefer: "resolution=merge-duplicates,return=representation",
    body: {
      id: 1,
      car_rate_per_hour: DEFAULT_CAR_RATE,
      bike_rate_per_hour: DEFAULT_BIKE_RATE
    }
  });
  const row = inserted?.[0] || {
    car_rate_per_hour: DEFAULT_CAR_RATE,
    bike_rate_per_hour: DEFAULT_BIKE_RATE
  };
  return {
    carRatePerHour: Number(row.car_rate_per_hour),
    bikeRatePerHour: Number(row.bike_rate_per_hour)
  };
}

async function handleSupabase(method, pathname, req) {
  if (method === "GET" && pathname === "/api/health") {
    try {
      await supabaseRest("parking_settings?id=eq.1&select=id");
      return { status: 200, body: { status: "UP", storageMode: "supabase" } };
    } catch (err) {
      if (err.isMissingTable) {
        return { status: 200, body: { status: "SETUP_REQUIRED", storageMode: "supabase-setup" } };
      }
      throw err;
    }
  }

  if (method === "GET" && pathname === "/api/settings/rates") {
    try {
      const rates = await getOrCreateSupabaseSettings();
      return { status: 200, body: rates };
    } catch (err) {
      if (err.isMissingTable) {
        return {
          status: 200,
          body: { carRatePerHour: DEFAULT_CAR_RATE, bikeRatePerHour: DEFAULT_BIKE_RATE }
        };
      }
      throw err;
    }
  }

  if (method === "PUT" && pathname === "/api/settings/rates") {
    const body = await readJsonBody(req);
    const { carRatePerHour, bikeRatePerHour } = validateRatesPayload(body);
    try {
      await supabaseRest("parking_settings?on_conflict=id", {
        method: "POST",
        prefer: "resolution=merge-duplicates,return=representation",
        body: {
          id: 1,
          car_rate_per_hour: carRatePerHour,
          bike_rate_per_hour: bikeRatePerHour,
          updated_at: new Date().toISOString()
        }
      });
      return { status: 200, body: { carRatePerHour, bikeRatePerHour } };
    } catch (err) {
      if (err.isMissingTable) {
        throw new HttpError(
          503,
          "Supabase tables are not initialized yet. Run supabase/schema.sql in your Supabase SQL Editor."
        );
      }
      throw err;
    }
  }

  if (method === "GET" && pathname === "/api/dashboard") {
    try {
      const [parkedRows, paidRows, settings] = await Promise.all([
        supabaseRest("parking_records?status=eq.PARKED&select=vehicle_type"),
        supabaseRest("parking_records?status=eq.PAID&select=amount"),
        getOrCreateSupabaseSettings()
      ]);

      const parkedCars = parkedRows.filter((r) => r.vehicle_type === "CAR").length;
      const parkedBikes = parkedRows.filter((r) => r.vehicle_type === "BIKE").length;
      const totalRevenue = paidRows.reduce((sum, r) => sum + Number(r.amount || 0), 0);

      return {
        status: 200,
        body: buildDashboardPayload({
          parkedCars,
          parkedBikes,
          carRatePerHour: settings.carRatePerHour,
          bikeRatePerHour: settings.bikeRatePerHour,
          totalCheckouts: paidRows.length,
          totalRevenue,
          storageMode: "supabase"
        })
      };
    } catch (err) {
      if (err.isMissingTable) {
        return {
          status: 200,
          body: buildDashboardPayload({
            parkedCars: 0,
            parkedBikes: 0,
            carRatePerHour: DEFAULT_CAR_RATE,
            bikeRatePerHour: DEFAULT_BIKE_RATE,
            totalCheckouts: 0,
            totalRevenue: 0,
            storageMode: "supabase-setup"
          })
        };
      }
      throw err;
    }
  }

  if (method === "GET" && pathname === "/api/parking/active") {
    try {
      const rows = await supabaseRest(
        "parking_records?status=eq.PARKED&order=entry_time.desc&select=*"
      );
      return { status: 200, body: rows.map(mapDbRecord) };
    } catch (err) {
      if (err.isMissingTable) return { status: 200, body: [] };
      throw err;
    }
  }

  if (method === "GET" && pathname === "/api/parking/history") {
    try {
      const rows = await supabaseRest(
        "parking_records?status=eq.PAID&order=exit_time.desc.nullslast,id.desc&limit=50&select=*"
      );
      return { status: 200, body: rows.map(mapDbRecord) };
    } catch (err) {
      if (err.isMissingTable) return { status: 200, body: [] };
      throw err;
    }
  }

  if (method === "POST" && pathname === "/api/parking/entry") {
    const body = await readJsonBody(req);
    const { vehicleType, brand, model, numberPlate } = validateEntryPayload(body);

    try {
      const existingPlate = await supabaseRest(
        `parking_records?status=eq.PARKED&number_plate=eq.${encodeURIComponent(numberPlate)}&limit=1&select=id`
      );
      if (existingPlate.length > 0) {
        throw new HttpError(400, "This number plate already has an active parking session.");
      }

      const activeSlots = await supabaseRest(
        `parking_records?status=eq.PARKED&vehicle_type=eq.${encodeURIComponent(vehicleType)}&select=slot_number`
      );
      const slot = pickFreeSlot(
        new Set(activeSlots.map((r) => r.slot_number)),
        vehicleType
      );
      if (!slot) {
        throw new HttpError(400, `No available ${vehicleType.toLowerCase()} spaces.`);
      }

      const created = await supabaseRest("parking_records", {
        method: "POST",
        prefer: "return=representation",
        body: {
          vehicle_type: vehicleType,
          brand,
          model,
          number_plate: numberPlate,
          slot_number: slot,
          entry_time: new Date().toISOString(),
          status: "PARKED"
        }
      });
      return { status: 201, body: mapDbRecord(created[0]) };
    } catch (err) {
      if (err instanceof HttpError) throw err;
      if (err.isMissingTable) {
        throw new HttpError(
          503,
          "Supabase tables are not initialized yet. Run supabase/schema.sql in your Supabase SQL Editor."
        );
      }
      if (err.pgCode === "23505") {
        throw new HttpError(400, "This number plate or slot is already active. Please retry.");
      }
      throw err;
    }
  }

  const checkoutMatch = pathname.match(/^\/api\/parking\/(\d+)\/checkout$/);
  if (method === "POST" && checkoutMatch) {
    const id = Number(checkoutMatch[1]);
    try {
      const found = await supabaseRest(`parking_records?id=eq.${id}&select=*`);
      if (!found || found.length === 0) {
        throw new HttpError(404, "Parking record not found.");
      }
      const existing = found[0];
      if (existing.status !== "PARKED") {
        throw new HttpError(400, "This vehicle has already checked out.");
      }

      const settings = await getOrCreateSupabaseSettings();
      const rate =
        existing.vehicle_type === "CAR"
          ? settings.carRatePerHour
          : settings.bikeRatePerHour;
      const { now, minutes, billableHours, amount } = computeBill(existing.entry_time, rate);

      const updated = await supabaseRest(
        `parking_records?id=eq.${id}&status=eq.PARKED`,
        {
          method: "PATCH",
          prefer: "return=representation",
          body: {
            exit_time: now.toISOString(),
            duration_minutes: minutes,
            amount,
            status: "PAID"
          }
        }
      );
      if (!updated || updated.length === 0) {
        throw new HttpError(400, "This vehicle has already checked out.");
      }

      return {
        status: 200,
        body: {
          record: mapDbRecord(updated[0]),
          billableHours,
          message: "Checkout completed and slot released."
        }
      };
    } catch (err) {
      if (err instanceof HttpError) throw err;
      if (err.isMissingTable) {
        throw new HttpError(
          503,
          "Supabase tables are not initialized yet. Run supabase/schema.sql in your Supabase SQL Editor."
        );
      }
      throw err;
    }
  }

  throw new HttpError(404, "API route not found.");
}

function loadTmpStore() {
  if (memoryStore) return memoryStore;
  try {
    if (fs.existsSync(TMP_STORE_PATH)) {
      const parsed = JSON.parse(fs.readFileSync(TMP_STORE_PATH, "utf8"));
      if (parsed && Array.isArray(parsed.records) && parsed.rates) {
        memoryStore = parsed;
        return memoryStore;
      }
    }
  } catch {
    // Fall back to fresh ephemeral state.
  }
  memoryStore = {
    nextId: 1,
    rates: {
      carRatePerHour: DEFAULT_CAR_RATE,
      bikeRatePerHour: DEFAULT_BIKE_RATE
    },
    records: []
  };
  return memoryStore;
}

function saveTmpStore(store) {
  memoryStore = store;
  try {
    fs.writeFileSync(TMP_STORE_PATH, JSON.stringify(store), "utf8");
  } catch {
    // Ignore read-only tmp issues; in-memory state remains active for warm lambda.
  }
}

async function handleEphemeral(method, pathname, req) {
  const store = loadTmpStore();

  if (method === "GET" && pathname === "/api/health") {
    return { status: 200, body: { status: "UP", storageMode: "ephemeral" } };
  }

  if (method === "GET" && pathname === "/api/settings/rates") {
    return { status: 200, body: { ...store.rates } };
  }

  if (method === "PUT" && pathname === "/api/settings/rates") {
    const body = await readJsonBody(req);
    store.rates = validateRatesPayload(body);
    saveTmpStore(store);
    return { status: 200, body: { ...store.rates } };
  }

  if (method === "GET" && pathname === "/api/dashboard") {
    const parked = store.records.filter((r) => r.status === "PARKED");
    const paid = store.records.filter((r) => r.status === "PAID");
    const parkedCars = parked.filter((r) => r.vehicleType === "CAR").length;
    const parkedBikes = parked.filter((r) => r.vehicleType === "BIKE").length;
    const totalRevenue = paid.reduce((sum, r) => sum + Number(r.amount || 0), 0);
    return {
      status: 200,
      body: buildDashboardPayload({
        parkedCars,
        parkedBikes,
        carRatePerHour: store.rates.carRatePerHour,
        bikeRatePerHour: store.rates.bikeRatePerHour,
        totalCheckouts: paid.length,
        totalRevenue,
        storageMode: "ephemeral"
      })
    };
  }

  if (method === "GET" && pathname === "/api/parking/active") {
    const active = store.records
      .filter((r) => r.status === "PARKED")
      .sort((a, b) => new Date(b.entryTime) - new Date(a.entryTime));
    return { status: 200, body: active };
  }

  if (method === "GET" && pathname === "/api/parking/history") {
    const history = store.records
      .filter((r) => r.status === "PAID")
      .sort((a, b) => new Date(b.exitTime || 0) - new Date(a.exitTime || 0))
      .slice(0, 50);
    return { status: 200, body: history };
  }

  if (method === "POST" && pathname === "/api/parking/entry") {
    const body = await readJsonBody(req);
    const { vehicleType, brand, model, numberPlate } = validateEntryPayload(body);

    const dup = store.records.find(
      (r) => r.status === "PARKED" && r.numberPlate.toUpperCase() === numberPlate
    );
    if (dup) {
      throw new HttpError(400, "This number plate already has an active parking session.");
    }

    const slot = pickFreeSlot(
      new Set(
        store.records
          .filter((r) => r.status === "PARKED" && r.vehicleType === vehicleType)
          .map((r) => r.slotNumber)
      ),
      vehicleType
    );
    if (!slot) {
      throw new HttpError(400, `No available ${vehicleType.toLowerCase()} spaces.`);
    }

    const record = {
      id: store.nextId++,
      vehicleType,
      brand,
      model,
      numberPlate,
      slotNumber: slot,
      entryTime: new Date().toISOString(),
      exitTime: null,
      durationMinutes: 0,
      amount: null,
      status: "PARKED"
    };
    store.records.push(record);
    saveTmpStore(store);
    return { status: 201, body: record };
  }

  const checkoutMatch = pathname.match(/^\/api\/parking\/(\d+)\/checkout$/);
  if (method === "POST" && checkoutMatch) {
    const id = Number(checkoutMatch[1]);
    const record = store.records.find((r) => r.id === id);
    if (!record) throw new HttpError(404, "Parking record not found.");
    if (record.status !== "PARKED") {
      throw new HttpError(400, "This vehicle has already checked out.");
    }

    const rate =
      record.vehicleType === "CAR"
        ? store.rates.carRatePerHour
        : store.rates.bikeRatePerHour;
    const { now, minutes, billableHours, amount } = computeBill(record.entryTime, rate);

    record.exitTime = now.toISOString();
    record.durationMinutes = minutes;
    record.amount = amount;
    record.status = "PAID";
    saveTmpStore(store);

    return {
      status: 200,
      body: {
        record,
        billableHours,
        message: "Checkout completed and slot released."
      }
    };
  }

  throw new HttpError(404, "API route not found.");
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.statusCode = 204;
    res.end();
    return;
  }

  try {
    const pathname = resolveApiPath(req);
    if (req.method === "GET" && pathname === "/api/config") {
      sendJson(res, 200, {
        supabaseConfigured: Boolean(supabaseUrl && supabaseSecretKey),
        supabaseUrl: supabaseUrl || null,
        supabasePublishableKey: supabasePublishableKey || null,
        supabaseJwksUrl: supabaseJwksUrl || null,
        storageMode: activeStorageModeLabel()
      });
      return;
    }

    let result;
    if (getPool()) {
      result = await handlePostgres(req.method, pathname, req);
    } else if (supabaseUrl && supabaseSecretKey) {
      result = await handleSupabase(req.method, pathname, req);
    } else {
      result = await handleEphemeral(req.method, pathname, req);
    }
    sendJson(res, result.status, result.body);
  } catch (err) {
    if (err instanceof HttpError) {
      sendJson(res, err.status, { error: err.message });
      return;
    }
    if (err instanceof SyntaxError) {
      sendJson(res, 400, { error: "Invalid JSON request body." });
      return;
    }
    console.error("API error:", err);
    sendJson(res, 500, { error: "Internal server error." });
  }
}
