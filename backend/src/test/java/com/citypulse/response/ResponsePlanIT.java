package com.citypulse.response;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Response plans, and the line the platform must not cross.
 *
 * <p>Exactly one step may come from the system — the recommendedAction a rule
 * attached when it fired. Every other line is text a person typed, and the two
 * must never be presented in the same voice: this is the screen where generated
 * text gets acted on rather than merely read.
 */
@DisplayName("Response plans")
class ResponsePlanIT extends IntegrationTest {

    private JsonNode create(String token, String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/response-plans")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("keeps every authored step, and marks none of them as the platform's")
    void authoredStepsAreNotAttributedToTheSystem() throws Exception {
        Tokens tokens = loginAs("plan-author@example.com", RoleName.CITY_OPERATOR);

        JsonNode plan = create(tokens.accessToken(), """
                {"title":"Evening corridor response","citySlug":"bengaluru","priority":"HIGH",
                 "steps":[{"instruction":"Monitor the alternate corridor"},
                          {"instruction":"Notify the response team"}]}
                """);

        assertThat(plan.path("stepsTotal").asInt()).isEqualTo(2);
        for (JsonNode step : plan.path("steps")) {
            // Nothing here came from a rule, and nothing may claim to have.
            assertThat(step.path("fromAlertRule").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("refuses a plan with no steps")
    void refusesAnEmptyPlan() throws Exception {
        Tokens tokens = loginAs("plan-empty@example.com", RoleName.CITY_OPERATOR);

        // A plan with no steps is a title. Filing it as a response would
        // overstate what actually exists.
        //
        // 422, not 400: this is @NotEmpty failing before the request reaches the
        // service. The service's own refusals — a foreign zone, a future start —
        // are 400. Two different things went wrong and the codes say which.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/response-plans")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Nothing yet","citySlug":"bengaluru","steps":[]}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a blocked step must say why")
    void blockingNeedsAReason() throws Exception {
        Tokens tokens = loginAs("plan-block@example.com", RoleName.CITY_OPERATOR);

        JsonNode plan = create(tokens.accessToken(), """
                {"title":"Response","citySlug":"bengaluru",
                 "steps":[{"instruction":"Divert traffic"}]}
                """);
        String planId = plan.path("id").asText();
        String stepId = plan.path("steps").get(0).path("id").asText();

        // A stalled step with no reason is a dead end nobody can pick up later.
        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/response-plans/" + planId + "/steps/" + stepId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reopening a step clears who finished it")
    void reopeningClearsCompletion() throws Exception {
        Tokens tokens = loginAs("plan-reopen@example.com", RoleName.CITY_OPERATOR);

        JsonNode plan = create(tokens.accessToken(), """
                {"title":"Response","citySlug":"bengaluru",
                 "steps":[{"instruction":"Divert traffic"}]}
                """);
        String planId = plan.path("id").asText();
        String stepId = plan.path("steps").get(0).path("id").asText();

        String done = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/response-plans/" + planId + "/steps/" + stepId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(done).path("data").path("steps").get(0)
                .path("completedBy").asText()).isEqualTo("plan-reopen@example.com");

        String reopened = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/response-plans/" + planId + "/steps/" + stepId)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Otherwise the plan shows a pending step that someone is recorded as
        // having finished.
        JsonNode step = objectMapper.readTree(reopened).path("data").path("steps").get(0);
        assertThat(notMeasured(step, "completedAt")).isTrue();
        assertThat(notMeasured(step, "completedBy")).isTrue();
    }

    @Test
    @DisplayName("counts progress per step, not per plan")
    void reportsPartialProgress() throws Exception {
        Tokens tokens = loginAs("plan-progress@example.com", RoleName.CITY_OPERATOR);

        JsonNode plan = create(tokens.accessToken(), """
                {"title":"Response","citySlug":"bengaluru",
                 "steps":[{"instruction":"One"},{"instruction":"Two"},{"instruction":"Three"}]}
                """);
        String planId = plan.path("id").asText();
        String first = plan.path("steps").get(0).path("id").asText();
        String second = plan.path("steps").get(1).path("id").asText();

        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/response-plans/" + planId + "/steps/" + first)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/response-plans/" + planId + "/steps/" + second)
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\",\"note\":\"Awaiting police sign-off\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // One status cannot say "three of five, with the fourth blocked".
        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("stepsDone").asInt()).isEqualTo(1);
        assertThat(data.path("stepsBlocked").asInt()).isEqualTo(1);
        assertThat(data.path("stepsTotal").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/response-plans?citySlug=bengaluru"))
                .andExpect(status().isUnauthorized());
    }
}
