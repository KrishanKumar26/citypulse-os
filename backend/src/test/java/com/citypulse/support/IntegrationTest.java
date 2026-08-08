package com.citypulse.support;

import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.User;
import com.citypulse.user.domain.UserStatus;
import com.citypulse.user.repository.RoleRepository;
import com.citypulse.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base class for integration tests. Boots the full application against a real
 * PostgreSQL database and drives it through the HTTP layer, so filters, the
 * security chain, validation, and the exception handler are all exercised —
 * the parts a service-level test would skip.
 *
 * <p>Tests are not wrapped in a rollback transaction. Several security
 * behaviours deliberately commit in {@code REQUIRES_NEW} transactions (lockout
 * counters, token-family revocation, audit entries), and a surrounding rollback
 * would hide exactly the behaviour under test. State is reset explicitly
 * instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** Satisfies the password policy; reused so tests read consistently. */
    protected static final String VALID_PASSWORD = "Str0ng!Passw0rd#2026";

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected EntityManager entityManager;

    protected MockMvc mockMvc;

    /**
     * Registered explicitly below. {@code webAppContextSetup} wires up the
     * security chain but not filters that Boot registers with the servlet
     * container, so without this the request-id correlation that production
     * traffic gets would be silently absent from every test.
     */
    @Autowired
    protected com.citypulse.common.web.RequestIdFilter requestIdFilter;

    /**
     * True when a field is absent or null in a response.
     *
     * <p>Deliberately {@code hasNonNull} rather than {@code path(field).isNull()}:
     * Jackson omits null fields entirely, so the latter returns false for a key
     * that was never serialised — an assertion that passes for the wrong reason.
     * Four assertions in this suite once did exactly that.
     */
    protected boolean notMeasured(JsonNode node, String field) {
        return !node.hasNonNull(field);
    }

    @BeforeEach
    void setUpIntegrationTest() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .apply(springSecurity())
                .build();
        resetMutableState();
    }

    /**
     * Highest city id the migrations seeded, read once before any test writes.
     *
     * <p>Was a hardcoded list of three slugs, which broke the moment V15 added
     * seven more cities: the reset tried to delete them and hit the foreign key
     * from zones. The same staleness the zone watermark below was introduced to
     * avoid — a list of what the migrations seed, kept by hand, is a list that
     * goes wrong the next time a migration seeds something.
     */
    private static Long seededCityWatermark;

    /**
     * Highest zone id that migration V3 seeded, read once on the first reset.
     *
     * <p>Static so it survives between test classes in a run: the first reset
     * happens before any test has written, which is the only moment the seeded
     * set can be identified without hard-coding a list of codes that would then
     * have to be kept in step with V3 by hand.
     *
     * <p>This assumes the database starts at the migrated state — true of CI,
     * which creates one per run, and of a fresh {@code createdb citypulse_test}.
     * A database already carrying a zone left behind by an older build would
     * have it counted as seeded and kept forever; recreate the database if the
     * suite starts failing on duplicate zone codes.
     */
    private static Long seededZoneWatermark;

    /**
     * Clears per-test data while leaving seeded reference data intact.
     *
     * <p>Roles and permissions come from migration V2 and the three demo cities
     * from V3; nearly every test reads them, so truncating would mean re-seeding
     * constantly. Everything a test can write is cleared.
     *
     * <p>Cities need the selective treatment rather than a truncate: a test that
     * creates a city would otherwise leave it behind, and the next run would fail
     * on a duplicate slug — a test failure caused purely by run order, which is
     * exactly the kind of flake that erodes trust in a suite.
     */
    protected void resetMutableState() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                            "TRUNCATE TABLE audit_logs, refresh_tokens, user_tokens, user_roles, users "
                            + "RESTART IDENTITY CASCADE")
                    .executeUpdate();

            // Curated telemetry and the alerts derived from it. Both are per-test
            // data: a leftover severe window would raise alerts in an unrelated
            // test, and a leftover alert would break another's counts — order-
            // dependent failures that look like flakes rather than bugs.
            entityManager.createNativeQuery(
                            "TRUNCATE TABLE alerts, zone_metrics RESTART IDENTITY CASCADE")
                    .executeUpdate();

            // Forecasts and the model runs behind them. A leftover ACTIVE run
            // would be picked up by the next test's queries and report someone
            // else's measured error.
            entityManager.createNativeQuery(
                            "TRUNCATE TABLE forecast_accuracy, forecasts, model_metrics, model_runs "
                            + "RESTART IDENTITY CASCADE")
                    .executeUpdate();

            entityManager.createNativeQuery(
                            "TRUNCATE TABLE simulation_results, simulations RESTART IDENTITY CASCADE")
                    .executeUpdate();

            // Intelligence outputs. A leftover anomaly or correlation would
            // appear in another test's summary and be counted as its own.
            entityManager.createNativeQuery(
                            "TRUNCATE TABLE anomalies, situation_memory, condition_correlations, "
                            + "zone_baselines RESTART IDENTITY CASCADE")
                    .executeUpdate();

            // Any zone a test created, including one added to a seeded city.
            //
            // This used to delete only zones belonging to non-seeded cities,
            // which left a hole: a test adding a zone to Bengaluru or Mumbai
            // leaked it into every later test in the run. One doing exactly that
            // turned five unrelated forecast tests into
            // NonUniqueResultException, because a lookup by zone code suddenly
            // matched two rows. That is the run-order flake the city cleanup
            // below was written to prevent, and zones needed the same care.
            //
            // The watermark is read on the first reset, before any test has
            // written, so it is the id of the last zone migration V3 seeded.
            if (seededZoneWatermark == null) {
                seededZoneWatermark = ((Number) entityManager
                        .createNativeQuery("SELECT coalesce(max(id), 0) FROM zones")
                        .getSingleResult()).longValue();
            }
            entityManager.createNativeQuery("DELETE FROM zones WHERE id > :watermark")
                    .setParameter("watermark", seededZoneWatermark)
                    .executeUpdate();

            // After the zones above, which reference them.
            if (seededCityWatermark == null) {
                seededCityWatermark = ((Number) entityManager
                        .createNativeQuery("SELECT coalesce(max(id), 0) FROM cities")
                        .getSingleResult()).longValue();
            }
            entityManager.createNativeQuery("DELETE FROM cities WHERE id > :watermark")
                    .setParameter("watermark", seededCityWatermark)
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Creates an active, verified user holding the named roles. */
    @Transactional
    protected User createUser(String email, String... roleNames) {
        List<Role> roles = roleRepository.findByNameInAndDeletedAtIsNull(Set.of(roleNames));
        if (roles.size() != roleNames.length) {
            throw new IllegalStateException("Unknown role in " + List.of(roleNames)
                                            + "; seeded roles come from migration V2");
        }

        User user = new User();
        user.setEmail(email.toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(VALID_PASSWORD));
        user.setFullName("Test " + email);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setPasswordChangedAt(Instant.now());
        user.getRoles().addAll(roles);
        return userRepository.saveAndFlush(user);
    }

    /** Signs in through the real login endpoint and returns the issued tokens. */
    protected Tokens login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login", new AuthRequests.Login(email, password)))
                .andReturn().getResponse().getContentAsString();
        var node = objectMapper.readTree(body).path("data");
        return new Tokens(node.path("accessToken").asText(), node.path("refreshToken").asText());
    }

    protected Tokens loginAs(String email, String... roleNames) throws Exception {
        createUser(email, roleNames);
        return login(email, VALID_PASSWORD);
    }

    // ------------------------------------------------------------------
    // Request helpers
    // ------------------------------------------------------------------

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String path, Object body) throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authGet(
            String path, String accessToken) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + accessToken);
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authPost(
            String path, String accessToken, Object body) throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected record Tokens(String accessToken, String refreshToken) {
    }
}
