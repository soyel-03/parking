# Decathlon Smart Parking

A full-stack parking operations dashboard with **Vercel Serverless + PostgreSQL** support and a **Java 17 + Spring Boot** backend.

## Features
- Responsive admin dashboard (`public/index.html` & `src/main/resources/static/index.html`)
- Vehicle entry registration (`CAR` / `BIKE`, brand, model, number plate) with automatic slot assignment (`C01`–`C30`, `B01`–`B20`)
- Live occupancy counts, active search filter, elapsed-time display, and revenue summary
- Database-persisted hourly rates (₹), rounded up to the next hour with a one-hour minimum
- Checkout billing calculation, automatic slot release, and persisted checkout history (`GET /api/parking/history`)
- PostgreSQL persistence in production with automatic H2 file fallback locally (Spring Boot) or ephemeral `/tmp` fallback on Vercel when no `DATABASE_URL` / `POSTGRES_URL` is configured yet

## Deploy on Vercel
The repository includes `vercel.json`, `public/`, and `api/index.js`:
1. Push to GitHub and deploy on Vercel (Framework Preset: **Other** / default).
2. For durable production storage across serverless invocations, add a PostgreSQL database (Vercel Postgres, Neon, or Supabase) and set `POSTGRES_URL` or `DATABASE_URL` in **Vercel Project Settings -> Environment Variables**, then redeploy.
   - Required tables (`parking_records`, `parking_settings`) are created automatically on first request.

## Run Locally (Spring Boot)
From this folder:
```bash
mvn spring-boot:run
```
Or using the packaged JAR:
```bash
mvn clean package
java -jar target/smart-parking-1.0.0.jar
```
Open http://localhost:8080

### Local H2 vs Production PostgreSQL (Spring Boot)
- **Default (H2 file DB)**: `jdbc:h2:file:./data/parkingdb;AUTO_SERVER=TRUE` (H2 console at http://localhost:8080/h2-console)
- **PostgreSQL**: set environment variables:
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
  - `SPRING_DATASOURCE_USERNAME=<user>`
  - `SPRING_DATASOURCE_PASSWORD=<password>`
  - `H2_CONSOLE_ENABLED=false`

## API Endpoints
- `GET /api/health`
- `GET /api/dashboard`
- `GET /api/parking/active`
- `GET /api/parking/history`
- `POST /api/parking/entry`
- `POST /api/parking/{id}/checkout`
- `GET /api/settings/rates`
- `PUT /api/settings/rates`
