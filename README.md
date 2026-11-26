# Decathlon Smart Parking

A full-stack parking operations dashboard with **Vercel Serverless + Supabase / PostgreSQL** support and a **Java 17 + Spring Boot** backend.

## Features
- Responsive admin dashboard (`public/index.html` & `src/main/resources/static/index.html`)
- Vehicle entry registration (`CAR` / `BIKE`, brand, model, number plate) with automatic slot assignment (`C01`–`C30`, `B01`–`B20`)
- Live occupancy counts, active search filter, elapsed-time display, and revenue summary
- Database-persisted hourly rates (₹), rounded up to the next hour with a one-hour minimum
- Checkout billing calculation, automatic slot release, and persisted checkout history (`GET /api/parking/history`)
- Supports **Supabase** (`SUPABASE_URL` + `SUPABASE_SECRET_KEY`), direct **PostgreSQL** (`POSTGRES_URL` / `DATABASE_URL` / `SPRING_DATASOURCE_URL`), and **H2** file database fallback locally

## Supabase Setup
1. Copy `.env.example` to `.env` for local runs (never commit `.env`—it is gitignored).
2. Run [`supabase/schema.sql`](supabase/schema.sql) once in your **Supabase SQL Editor** to create `parking_records`, `parking_settings`, unique active-slot/plate indexes, and RLS policies.
3. In **Vercel Project Settings -> Environment Variables**, add:
   - `SUPABASE_URL`
   - `SUPABASE_PUBLISHABLE_KEY`
   - `SUPABASE_SECRET_KEY`
   - `SUPABASE_JWKS_URL`
   *(Or set `POSTGRES_URL` / `DATABASE_URL` if using a direct PostgreSQL connection string, which auto-creates the tables on first request.)*
4. Redeploy on Vercel.

## Run Locally (Spring Boot)
Spring Boot automatically loads `.env` if present (`spring.config.import=optional:file:.env[.properties]`):
```bash
mvn spring-boot:run
```
Or using the packaged JAR:
```bash
mvn clean package
java -jar target/smart-parking-1.0.0.jar
```
Open http://localhost:8080

## API Endpoints
- `GET /api/health`
- `GET /api/config`
- `GET /api/dashboard`
- `GET /api/parking/active`
- `GET /api/parking/history`
- `POST /api/parking/entry`
- `POST /api/parking/{id}/checkout`
- `GET /api/settings/rates`
- `PUT /api/settings/rates`
