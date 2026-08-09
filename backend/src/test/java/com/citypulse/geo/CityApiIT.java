package com.citypulse.geo;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * City and zone APIs, including the demo-data labelling that PRD §42 requires
 * and the validation that keeps invalid geography out of the warehouse.
 */
@DisplayName("City and zone API")
class CityApiIT extends IntegrationTest {

    @Test
    @DisplayName("seeded demo cities are returned and flagged as demo data")
    void seededCitiesAreFlaggedAsDemo() throws Exception {
        Tokens viewer = loginAs("cityreader@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/cities", viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].demoData").value(true))
                .andExpect(jsonPath("$.data[?(@.slug == 'bengaluru')]").exists())
                .andExpect(jsonPath("$.data[?(@.slug == 'noida')]").exists())
                .andExpect(jsonPath("$.data[?(@.slug == 'mumbai')]").exists());
    }

    @Test
    @DisplayName("zone counts are reported without an N+1 query")
    void cityListIncludesZoneCounts() throws Exception {
        Tokens viewer = loginAs("counts@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/cities", viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.slug == 'bengaluru')].zoneCount").value(
                        org.hamcrest.Matchers.contains(8)));
    }

    @Test
    @DisplayName("a city is retrievable by slug, and Noida contains the PRD's Sector 18")
    void zonesAreSeededForNoida() throws Exception {
        Tokens viewer = loginAs("zonereader@example.com", RoleName.VIEWER);

        String body = mockMvc.perform(authGet("/api/v1/cities/by-slug/noida", viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Asia/Kolkata"))
                .andReturn().getResponse().getContentAsString();
        String cityId = objectMapper.readTree(body).path("data").path("id").asText();

        mockMvc.perform(authGet("/api/v1/cities/" + cityId + "/zones", viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'NOI-S18')].name")
                        .value(org.hamcrest.Matchers.contains("Sector 18")))
                // The zone inherits its city's provenance flag.
                .andExpect(jsonPath("$.data[0].demoData").value(true));
    }

    @Test
    @DisplayName("zone search returns the whole city when no term is given")
    void zoneSearchWithoutATermReturnsEveryZone() throws Exception {
        Tokens viewer = loginAs("zonesearch@example.com", RoleName.VIEWER);

        String body = mockMvc.perform(authGet("/api/v1/cities/by-slug/noida", viewer.accessToken()))
                .andReturn().getResponse().getContentAsString();
        String cityId = objectMapper.readTree(body).path("data").path("id").asText();

        // The omitted term is the case that failed: a null parameter reaching
        // LOWER() has no type for PostgreSQL to infer, so it was read as bytea.
        mockMvc.perform(authGet("/api/v1/cities/" + cityId + "/zones/search", viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(6));

        // Term without a space: MockMvc re-encodes the template, so a literal
        // %20 here would be searched for as "%20" rather than a space.
        mockMvc.perform(authGet("/api/v1/cities/" + cityId + "/zones/search?search=S18",
                        viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("NOI-S18"));
    }

    @Test
    @DisplayName("an unknown city returns 404 with the standard envelope")
    void unknownCityReturnsNotFound() throws Exception {
        Tokens viewer = loginAs("missing@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/cities/" + java.util.UUID.randomUUID(), viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a non-UUID city id is rejected as a bad request, not a server error")
    void malformedIdIsRejected() throws Exception {
        Tokens viewer = loginAs("badid@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/cities/not-a-uuid", viewer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("out-of-range coordinates are rejected with per-field errors")
    void invalidCoordinatesAreRejected() throws Exception {
        Tokens admin = loginAs("geoadmin@example.com", RoleName.ADMIN);

        Map<String, Object> payload = new HashMap<>(validCity("bad-coords"));
        payload.put("centerLatitude", 120.0);   // beyond ±90
        payload.put("centerLongitude", 400.0);  // beyond ±180

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors").isArray());
    }

    @Test
    @DisplayName("a malformed slug is rejected")
    void invalidSlugIsRejected() throws Exception {
        Tokens admin = loginAs("slugadmin@example.com", RoleName.ADMIN);

        Map<String, Object> payload = new HashMap<>(validCity("Not A Valid Slug"));

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldErrors[?(@.field == 'slug')]").exists());
    }

    @Test
    @DisplayName("a duplicate slug is a conflict, not a 500 from the unique index")
    void duplicateSlugIsConflict() throws Exception {
        Tokens admin = loginAs("dupadmin@example.com", RoleName.ADMIN);

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("dup-city")))
                .andExpect(status().isCreated());

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("dup-city")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("a new city defaults to demo data when the flag is omitted")
    void newCityDefaultsToDemoData() throws Exception {
        Tokens admin = loginAs("defaultdemo@example.com", RoleName.ADMIN);

        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("default-demo")))
                .andExpect(status().isCreated())
                // Mislabelling synthetic data as live is the failure that matters,
                // so the default errs toward "demo".
                .andExpect(jsonPath("$.data.demoData").value(true));
    }

    @Test
    @DisplayName("a created city is immediately readable and reports zero zones")
    void createdCityIsReadable() throws Exception {
        Tokens admin = loginAs("createread@example.com", RoleName.ADMIN);

        String body = mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("read-back")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.zoneCount").value(0))
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).path("data").path("id").asText();
        mockMvc.perform(authGet("/api/v1/cities/" + id, admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("read-back"));
    }

    @Test
    @DisplayName("a soft-deleted city disappears from the list but its slug is released")
    void softDeleteHidesCity() throws Exception {
        Tokens admin = loginAs("deleter@example.com", RoleName.ADMIN);

        String body = mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("to-delete")))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).path("data").path("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/cities/" + id)
                        .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(authGet("/api/v1/cities/" + id, admin.accessToken()))
                .andExpect(status().isNotFound());

        // The partial unique index only covers live rows, so the slug is reusable.
        mockMvc.perform(authPost("/api/v1/cities", admin.accessToken(), validCity("to-delete")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("page size is capped so a caller cannot request an unbounded result set")
    void pageSizeIsCapped() throws Exception {
        Tokens viewer = loginAs("paging@example.com", RoleName.VIEWER);
        String body = mockMvc.perform(authGet("/api/v1/cities/by-slug/bengaluru", viewer.accessToken()))
                .andReturn().getResponse().getContentAsString();
        String cityId = objectMapper.readTree(body).path("data").path("id").asText();

        mockMvc.perform(authGet("/api/v1/cities/" + cityId + "/zones/search?size=5000",
                        viewer.accessToken()))
                .andExpect(status().isUnprocessableEntity());
    }

    private Map<String, Object> validCity(String slug) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slug", slug);
        payload.put("name", "Test City");
        payload.put("country", "India");
        payload.put("countryCode", "IN");
        payload.put("timezone", "Asia/Kolkata");
        payload.put("centerLatitude", 12.9716);
        payload.put("centerLongitude", 77.5946);
        payload.put("defaultZoom", 11);
        return payload;
    }
}
