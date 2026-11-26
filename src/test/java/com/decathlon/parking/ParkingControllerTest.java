package com.decathlon.parking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:parkingtest;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "supabase.url=",
    "supabase.secret-key="
})
@AutoConfigureMockMvc
class ParkingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullParkingLifecycleAndPersistedRates() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(put("/api/settings/rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carRatePerHour\":45,\"bikeRatePerHour\":20}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.carRatePerHour").value(45.0))
            .andExpect(jsonPath("$.bikeRatePerHour").value(20.0));

        MvcResult entryResult = mockMvc.perform(post("/api/parking/entry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleType\":\"CAR\",\"brand\":\"Hyundai\",\"model\":\"Creta\",\"numberPlate\":\"WB02AB1234\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slotNumber").value("C01"))
            .andExpect(jsonPath("$.status").value("PARKED"))
            .andReturn();

        JsonNode created = objectMapper.readTree(entryResult.getResponse().getContentAsString());
        long id = created.get("id").asLong();

        mockMvc.perform(post("/api/parking/entry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleType\":\"CAR\",\"brand\":\"Hyundai\",\"model\":\"Creta\",\"numberPlate\":\"WB02AB1234\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/api/parking/entry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleType\":\"CAR\",\"brand\":\"   \",\"model\":\"Creta\",\"numberPlate\":\"WB02AB9999\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/api/parking/" + id + "/checkout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.billableHours").value(1))
            .andExpect(jsonPath("$.record.amount").value(45.0))
            .andExpect(jsonPath("$.record.status").value("PAID"));

        mockMvc.perform(get("/api/parking/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].numberPlate").value("WB02AB1234"))
            .andExpect(jsonPath("$[0].status").value("PAID"));

        mockMvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parkedCars").value(0))
            .andExpect(jsonPath("$.totalCheckouts").value(1))
            .andExpect(jsonPath("$.totalRevenue").value(45.0));
    }
}
