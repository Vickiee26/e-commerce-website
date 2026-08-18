package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AddressControllerIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String COMPLETE_ADDRESS = """
            {"recipientName":"Ada Lovelace","phone":"+15550100","line1":"12 Analytical Way",
             "line2":"Flat 3","city":"London","state":"Greater London","postalCode":"E1 6AN",
             "country":"GB"}
            """;

    /** Creates an address over HTTP and returns its id, so tests exercise the real path. */
    private UUID createAddress(User user, String body) throws Exception {
        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void requiresAuthenticationOnEveryAddressEndpoint() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/api/me/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mockMvc.perform(post("/api/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_ADDRESS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/me/addresses/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"Paris"}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/me/addresses/" + id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsAnAddressAndReturnsItWithALocationHeader() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_ADDRESS))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.recipientName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.line1").value("12 Analytical Way"))
                .andExpect(jsonPath("$.line2").value("Flat 3"))
                .andExpect(jsonPath("$.city").value("London"))
                .andExpect(jsonPath("$.postalCode").value("E1 6AN"))
                .andExpect(jsonPath("$.country").value("GB"))
                .andExpect(jsonPath("$.defaultShipping").value(false))
                .andExpect(jsonPath("$.defaultBilling").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM addresses WHERE user_id = ?", Integer.class, user.getId()))
                .isEqualTo(1);
    }

    @Test
    void rejectsAnAddressMissingRequiredFields() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"+15550100"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'recipientName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'line1')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'city')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'postalCode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'country')]").exists());
    }

    @Test
    void rejectsACountryThatIsNotATwoLetterCode() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_ADDRESS.replace("\"GB\"", "\"United Kingdom\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'country')]").exists());
    }

    @Test
    void storesTheCountryInUppercase() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_ADDRESS.replace("\"GB\"", "\"gb\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.country").value("GB"));
    }

    @Test
    void rejectsAnOverlongPostalCode() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_ADDRESS.replace("\"E1 6AN\"", "\"" + "9".repeat(21) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'postalCode')]").exists());
    }

    @Test
    void returnsAnEmptyListWhenTheCallerHasNoAddresses() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(get("/api/me/addresses").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listsOnlyTheCallersOwnAddresses() throws Exception {
        User ada = testData.createCustomer("ada@example.com", "Password1!x");
        User grace = testData.createCustomer("grace@example.com", "Password1!x");
        createAddress(ada, COMPLETE_ADDRESS);
        createAddress(grace, COMPLETE_ADDRESS.replace("Ada Lovelace", "Grace Hopper"));

        mockMvc.perform(get("/api/me/addresses").header(HttpHeaders.AUTHORIZATION, bearer(ada)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recipientName").value("Ada Lovelace"));
    }

    @Test
    void updatesOnlyTheSuppliedFields() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID id = createAddress(user, COMPLETE_ADDRESS);

        mockMvc.perform(patch("/api/me/addresses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"Manchester"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Manchester"))
                .andExpect(jsonPath("$.line1").value("12 Analytical Way"))
                .andExpect(jsonPath("$.recipientName").value("Ada Lovelace"));
    }

    @Test
    void clearsAnOptionalFieldGivenAnEmptyString() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID id = createAddress(user, COMPLETE_ADDRESS);

        mockMvc.perform(patch("/api/me/addresses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"line2":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line2").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT line2 FROM addresses WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    void rejectsBlankingARequiredFieldOnUpdate() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID id = createAddress(user, COMPLETE_ADDRESS);

        mockMvc.perform(patch("/api/me/addresses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"line1":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'line1')]").exists());
    }

    @Test
    void deletesTheCallersOwnAddress() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID id = createAddress(user, COMPLETE_ADDRESS);

        mockMvc.perform(delete("/api/me/addresses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me/addresses").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void answersNotFoundRatherThanForbiddenForAnotherUsersAddress() throws Exception {
        User ada = testData.createCustomer("ada@example.com", "Password1!x");
        User grace = testData.createCustomer("grace@example.com", "Password1!x");
        UUID gracesAddress = createAddress(grace, COMPLETE_ADDRESS);

        // 404, not 403: a 403 would confirm the id exists.
        mockMvc.perform(patch("/api/me/addresses/" + gracesAddress)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ada))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"Hijacked"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"));

        mockMvc.perform(delete("/api/me/addresses/" + gracesAddress)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ada)))
                .andExpect(status().isNotFound());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT city FROM addresses WHERE id = ?", gracesAddress);
        assertThat(row.get("city")).isEqualTo("London");
    }

    @Test
    void answersNotFoundForAnUnknownAddressId() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(delete("/api/me/addresses/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsAtMostOneDefaultShippingAddress() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID first = createAddress(user,
                COMPLETE_ADDRESS.replace("\"country\":\"GB\"",
                        "\"country\":\"GB\",\"defaultShipping\":true"));
        UUID second = createAddress(user, COMPLETE_ADDRESS.replace("Flat 3", "Flat 4"));

        mockMvc.perform(patch("/api/me/addresses/" + second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultShipping":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultShipping").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default_shipping FROM addresses WHERE id = ?", Boolean.class, first))
                .isFalse();
    }

    @Test
    void treatsShippingAndBillingDefaultsIndependently() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        UUID shipping = createAddress(user,
                COMPLETE_ADDRESS.replace("\"country\":\"GB\"",
                        "\"country\":\"GB\",\"defaultShipping\":true"));
        UUID billing = createAddress(user,
                COMPLETE_ADDRESS.replace("\"country\":\"GB\"",
                        "\"country\":\"GB\",\"defaultBilling\":true"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default_shipping FROM addresses WHERE id = ?", Boolean.class, shipping))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default_billing FROM addresses WHERE id = ?", Boolean.class, billing))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default_billing FROM addresses WHERE id = ?", Boolean.class, shipping))
                .isFalse();
    }

    @Test
    void rejectsAMalformedAddressId() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(delete("/api/me/addresses/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
