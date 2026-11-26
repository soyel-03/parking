package com.decathlon.parking.service;

import com.decathlon.parking.model.ParkingRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class SupabaseRestClient {
    private static final int CAR_CAPACITY = 30;
    private static final int BIKE_CAPACITY = 20;
    private static final BigDecimal DEFAULT_CAR_RATE = new BigDecimal("30.00");
    private static final BigDecimal DEFAULT_BIKE_RATE = new BigDecimal("15.00");

    private final String supabaseUrl;
    private final String secretKey;
    private final String publishableKey;
    private final String jwksUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public static class MissingTableException extends RuntimeException {
        public MissingTableException(String message) { super(message); }
    }

    public SupabaseRestClient(
        @Value("${supabase.url:}") String supabaseUrl,
        @Value("${supabase.secret-key:}") String secretKey,
        @Value("${supabase.publishable-key:}") String publishableKey,
        @Value("${supabase.jwks-url:}") String jwksUrl,
        ObjectMapper objectMapper
    ) {
        this.supabaseUrl = supabaseUrl == null ? "" : supabaseUrl.replaceAll("/+$", "");
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.publishableKey = publishableKey == null ? "" : publishableKey.trim();
        this.jwksUrl = jwksUrl == null ? "" : jwksUrl.trim();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    public boolean isConfigured() {
        return !supabaseUrl.isBlank() && !secretKey.isBlank();
    }

    public String getSupabaseUrl() { return supabaseUrl; }
    public String getPublishableKey() { return publishableKey; }
    public String getJwksUrl() { return jwksUrl; }

    private JsonNode rest(String path, String method, Map<String, Object> body, String prefer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/" + path))
                .timeout(Duration.ofSeconds(12))
                .header("apikey", secretKey)
                .header("User-Agent", "decathlon-parking-server/1.0")
                .header("Accept", "application/json");
            if (prefer != null && !prefer.isBlank()) {
                builder.header("Prefer", prefer);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = (raw != null && !raw.isBlank()) ? objectMapper.readTree(raw) : objectMapper.createArrayNode();

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = json.hasNonNull("code") ? json.get("code").asText() : "";
                String msg = json.hasNonNull("message") ? json.get("message").asText()
                    : "Supabase request failed (" + response.statusCode() + ")";
                if ("PGRST205".equals(code) || "42P01".equals(code)
                    || (response.statusCode() == 404 && msg.toLowerCase(Locale.ROOT).contains("schema cache"))) {
                    throw new MissingTableException(
                        "Supabase tables are not initialized yet. Run supabase/schema.sql in your Supabase SQL Editor."
                    );
                }
                if ("23505".equals(code)) {
                    throw new IllegalArgumentException("This number plate or slot is already active.");
                }
                throw new IllegalStateException(msg);
            }
            return json;
        } catch (MissingTableException | IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Supabase request failed: " + e.getMessage(), e);
        }
    }

    private ParkingRecord mapRecord(JsonNode row) {
        ParkingRecord r = new ParkingRecord();
        try {
            var idField = ParkingRecord.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(r, row.get("id").asLong());
        } catch (ReflectiveOperationException ignored) {}
        r.setVehicleType(row.get("vehicle_type").asText());
        r.setBrand(row.get("brand").asText());
        r.setModel(row.get("model").asText());
        r.setNumberPlate(row.get("number_plate").asText());
        r.setSlotNumber(row.get("slot_number").asText());
        r.setEntryTime(parseTimestamp(row.get("entry_time").asText()));
        if (row.hasNonNull("exit_time")) {
            r.setExitTime(parseTimestamp(row.get("exit_time").asText()));
        }
        r.setDurationMinutes(row.hasNonNull("duration_minutes") ? row.get("duration_minutes").asLong() : 0L);
        if (row.hasNonNull("amount")) {
            r.setAmount(new BigDecimal(row.get("amount").asText()).setScale(2, RoundingMode.HALF_UP));
        }
        r.setStatus(row.get("status").asText());
        return r;
    }

    private LocalDateTime parseTimestamp(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    public Map<String, BigDecimal> getOrCreateRates() {
        JsonNode rows = rest("parking_settings?id=eq.1&select=*", "GET", null, null);
        if (rows.isArray() && !rows.isEmpty()) {
            JsonNode row = rows.get(0);
            return Map.of(
                "carRatePerHour", new BigDecimal(row.get("car_rate_per_hour").asText()).setScale(2, RoundingMode.HALF_UP),
                "bikeRatePerHour", new BigDecimal(row.get("bike_rate_per_hour").asText()).setScale(2, RoundingMode.HALF_UP)
            );
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("id", 1L);
        defaults.put("car_rate_per_hour", DEFAULT_CAR_RATE);
        defaults.put("bike_rate_per_hour", DEFAULT_BIKE_RATE);
        rest("parking_settings?on_conflict=id", "POST", defaults, "resolution=merge-duplicates,return=representation");
        return Map.of("carRatePerHour", DEFAULT_CAR_RATE, "bikeRatePerHour", DEFAULT_BIKE_RATE);
    }

    public Map<String, Object> updateRates(BigDecimal car, BigDecimal bike) {
        BigDecimal c = car.setScale(2, RoundingMode.HALF_UP);
        BigDecimal b = bike.setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", 1L);
        payload.put("car_rate_per_hour", c);
        payload.put("bike_rate_per_hour", b);
        payload.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        rest("parking_settings?on_conflict=id", "POST", payload, "resolution=merge-duplicates,return=representation");
        return Map.of("carRatePerHour", c, "bikeRatePerHour", b);
    }

    public ParkingRecord enter(String vehicleType, String brand, String model, String normalizedPlate) {
        String encodedPlate = URLEncoder.encode(normalizedPlate, StandardCharsets.UTF_8);
        JsonNode existing = rest(
            "parking_records?status=eq.PARKED&number_plate=eq." + encodedPlate + "&limit=1&select=id",
            "GET", null, null
        );
        if (existing.isArray() && !existing.isEmpty()) {
            throw new IllegalArgumentException("This number plate already has an active parking session.");
        }

        JsonNode takenRows = rest(
            "parking_records?status=eq.PARKED&vehicle_type=eq." + vehicleType + "&select=slot_number",
            "GET", null, null
        );
        Set<String> taken = new HashSet<>();
        for (JsonNode row : takenRows) {
            taken.add(row.get("slot_number").asText());
        }

        int capacity = "CAR".equals(vehicleType) ? CAR_CAPACITY : BIKE_CAPACITY;
        String prefix = "CAR".equals(vehicleType) ? "C" : "B";
        String slot = null;
        for (int i = 1; i <= capacity; i++) {
            String candidate = prefix + String.format("%02d", i);
            if (!taken.contains(candidate)) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) {
            throw new IllegalStateException("No available " + vehicleType.toLowerCase(Locale.ROOT) + " spaces.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicle_type", vehicleType);
        payload.put("brand", brand.trim());
        payload.put("model", model.trim());
        payload.put("number_plate", normalizedPlate);
        payload.put("slot_number", slot);
        payload.put("entry_time", OffsetDateTime.now(ZoneOffset.UTC).toString());
        payload.put("status", "PARKED");

        JsonNode created = rest("parking_records", "POST", payload, "return=representation");
        return mapRecord(created.get(0));
    }

    public Map<String, Object> checkout(Long id) {
        JsonNode found = rest("parking_records?id=eq." + id + "&select=*", "GET", null, null);
        if (!found.isArray() || found.isEmpty()) {
            throw new NoSuchElementException("Parking record not found.");
        }
        JsonNode existing = found.get(0);
        if (!"PARKED".equals(existing.get("status").asText())) {
            throw new IllegalArgumentException("This vehicle has already checked out.");
        }

        Map<String, BigDecimal> rates = getOrCreateRates();
        BigDecimal rate = "CAR".equals(existing.get("vehicle_type").asText())
            ? rates.get("carRatePerHour")
            : rates.get("bikeRatePerHour");

        OffsetDateTime entryUtc = OffsetDateTime.parse(existing.get("entry_time").asText());
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        long minutes = Math.max(1, Duration.between(entryUtc, nowUtc).toMinutes());
        long billableHours = Math.max(1, (long) Math.ceil(minutes / 60.0));
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(billableHours)).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("exit_time", nowUtc.toString());
        patch.put("duration_minutes", minutes);
        patch.put("amount", amount);
        patch.put("status", "PAID");

        JsonNode updated = rest("parking_records?id=eq." + id + "&status=eq.PARKED", "PATCH", patch, "return=representation");
        if (!updated.isArray() || updated.isEmpty()) {
            throw new IllegalArgumentException("This vehicle has already checked out.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", mapRecord(updated.get(0)));
        result.put("billableHours", billableHours);
        result.put("message", "Checkout completed and slot released.");
        return result;
    }

    public List<ParkingRecord> active() {
        try {
            JsonNode rows = rest("parking_records?status=eq.PARKED&order=entry_time.desc&select=*", "GET", null, null);
            List<ParkingRecord> list = new ArrayList<>();
            for (JsonNode row : rows) list.add(mapRecord(row));
            return list;
        } catch (MissingTableException e) {
            return List.of();
        }
    }

    public List<ParkingRecord> history() {
        try {
            JsonNode rows = rest("parking_records?status=eq.PAID&order=exit_time.desc.nullslast,id.desc&limit=50&select=*", "GET", null, null);
            List<ParkingRecord> list = new ArrayList<>();
            for (JsonNode row : rows) list.add(mapRecord(row));
            return list;
        } catch (MissingTableException e) {
            return List.of();
        }
    }

    public Map<String, Object> dashboard() {
        try {
            JsonNode parkedRows = rest("parking_records?status=eq.PARKED&select=vehicle_type", "GET", null, null);
            JsonNode paidRows = rest("parking_records?status=eq.PAID&select=amount", "GET", null, null);
            Map<String, BigDecimal> rates = getOrCreateRates();

            long cars = 0, bikes = 0;
            for (JsonNode row : parkedRows) {
                String vt = row.get("vehicle_type").asText();
                if ("CAR".equals(vt)) cars++;
                else if ("BIKE".equals(vt)) bikes++;
            }
            BigDecimal revenue = BigDecimal.ZERO;
            for (JsonNode row : paidRows) {
                if (row.hasNonNull("amount")) {
                    revenue = revenue.add(new BigDecimal(row.get("amount").asText()));
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("parkedCars", cars);
            m.put("parkedBikes", bikes);
            m.put("totalParked", cars + bikes);
            m.put("availableCarSlots", CAR_CAPACITY - cars);
            m.put("availableBikeSlots", BIKE_CAPACITY - bikes);
            m.put("carCapacity", CAR_CAPACITY);
            m.put("bikeCapacity", BIKE_CAPACITY);
            m.put("carRatePerHour", rates.get("carRatePerHour"));
            m.put("bikeRatePerHour", rates.get("bikeRatePerHour"));
            m.put("totalCheckouts", (long) paidRows.size());
            m.put("totalRevenue", revenue.setScale(2, RoundingMode.HALF_UP));
            m.put("storageMode", "supabase");
            return m;
        } catch (MissingTableException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("parkedCars", 0L);
            m.put("parkedBikes", 0L);
            m.put("totalParked", 0L);
            m.put("availableCarSlots", (long) CAR_CAPACITY);
            m.put("availableBikeSlots", (long) BIKE_CAPACITY);
            m.put("carCapacity", CAR_CAPACITY);
            m.put("bikeCapacity", BIKE_CAPACITY);
            m.put("carRatePerHour", DEFAULT_CAR_RATE);
            m.put("bikeRatePerHour", DEFAULT_BIKE_RATE);
            m.put("totalCheckouts", 0L);
            m.put("totalRevenue", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            m.put("storageMode", "supabase-setup");
            return m;
        }
    }

    public String storageMode() {
        try {
            rest("parking_settings?id=eq.1&select=id", "GET", null, null);
            return "supabase";
        } catch (MissingTableException e) {
            return "supabase-setup";
        }
    }
}
