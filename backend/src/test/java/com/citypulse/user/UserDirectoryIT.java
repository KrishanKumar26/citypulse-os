package com.citypulse.user;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.RoleName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user directory, which had no coverage at all and was returning 500 to
 * every caller: the listing query carried an {@code @EntityGraph} over the
 * {@code roles} collection, and Hibernate will not paginate a collection fetch
 * join. The listing is paginated by definition, so the two could never agree.
 *
 * <p>Each test below asks for a page — that is the whole point. A test that
 * fetched a single user would have passed throughout the outage.
 */
@DisplayName("User directory API")
class UserDirectoryIT extends IntegrationTest {

    @Test
    @DisplayName("a page of users is returned with each account's roles")
    void listReturnsPagedUsersWithRoles() throws Exception {
        Tokens admin = loginAs("directory-admin@example.com", RoleName.SUPER_ADMIN);
        loginAs("directory-viewer@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/users?size=20", admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[?(@.email == 'directory-viewer@example.com')].roles")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.contains("VIEWER"))));
    }

    @Test
    @DisplayName("the page size is honoured rather than silently paged in memory")
    void listHonoursPageSize() throws Exception {
        Tokens admin = loginAs("page-admin@example.com", RoleName.SUPER_ADMIN);
        loginAs("page-one@example.com", RoleName.VIEWER);
        loginAs("page-two@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/users?size=1", admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("search narrows the page to matching accounts")
    void searchFiltersTheDirectory() throws Exception {
        Tokens admin = loginAs("search-admin@example.com", RoleName.SUPER_ADMIN);
        loginAs("findme-unique@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/users?search=findme-unique", admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].email").value("findme-unique@example.com"));
    }

    @Test
    @DisplayName("an account without user:read cannot read the directory")
    void viewerCannotListUsers() throws Exception {
        Tokens viewer = loginAs("nosy-viewer@example.com", RoleName.VIEWER);

        mockMvc.perform(authGet("/api/v1/users", viewer.accessToken()))
                .andExpect(status().isForbidden());
    }
}
