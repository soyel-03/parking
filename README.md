# Decathlon Smart Parking (Java + Spring Boot)

A runnable MVP for a single-site parking operation.

## Included
- Responsive admin dashboard served by Spring Boot
- Vehicle entry registration: CAR/BIKE, brand, model and number plate
- Live vehicle counts derived from active parking records
- Automatic slot assignment: C01-C30 for cars and B01-B20 for bikes
- Entry timestamp and live elapsed-time display
- Hourly configurable rates (₹), rounded up to the next hour with a one-hour minimum
- Checkout bill calculation and automatic slot release
- H2 file database for local persistence

## Requirements
- Java 17+
- Maven 3.9+

## Run
From this folder:
```bash
mvn spring-boot:run
```
Open http://localhost:8080

H2 database console: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:file:./data/parkingdb`
- User: `sa`
- Password: (blank)

## API
- `GET /api/dashboard`
- `GET /api/parking/active`
- `POST /api/parking/entry`
  ```json
  {"vehicleType":"CAR","brand":"Hyundai","model":"Creta","numberPlate":"WB02AB1234"}
  ```
- `POST /api/parking/{id}/checkout`
- `GET /api/settings/rates`
- `PUT /api/settings/rates`
  ```json
  {"carRatePerHour":30,"bikeRatePerHour":15}
  ```

## Important production integrations
This is a working software MVP, not yet a fully automated gate installation:
1. **Automatic physical vehicle detection and number-plate reading** require entry/exit cameras or loop/IR sensors, an ANPR/LPR service or edge device, and a secure integration endpoint. The current form simulates the captured vehicle details.
2. **Real payment collection** requires a payment provider (for example Razorpay/UPI/card), server-side order creation, signed webhook verification and reconciliation. The included checkout is explicitly demo-only and does not collect money.
3. Configure the actual Decathlon site's slot capacity, rates, tax rules, grace period, overnight policy and approved parking rules before deployment.
4. Add authentication/roles, audit logs, HTTPS, backups, rate limiting and privacy/retention controls before using real customer number plates.
5. Rates are held in application memory in this MVP and reset to defaults when the server restarts. Persist settings in a database for production.
