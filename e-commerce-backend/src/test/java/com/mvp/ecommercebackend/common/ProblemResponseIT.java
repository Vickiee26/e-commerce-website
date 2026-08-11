package com.mvp.ecommercebackend.common;

import com.mvp.ecommercebackend.auth.TokenService;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance criterion 7: every error response is {@code application/problem+json}.
 *
 * <p>The per-feature tests each check the content type on one error they happen to produce. This
 * sweeps one error of every status the API returns and checks the same three things about all of
 * them: the content type, the RFC 7807 members, and — the security-relevant part — that no exception
 * class, stack frame or internal message reaches the client.
 */
class ProblemResponseIT extends AbstractIntegrationTest {

    @Autowired
    private TokenService tokenService;

    @Test
    void unauthenticatedRequestIsAProblem() throws Exception {
        assertProblem(get("/api/me"), 401, "/api/me");
    }

    @Test
    void expiredOrGarbledTokenIsAProblem() throws Exception {
        assertProblem(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"),
                401, "/api/me");
    }

    @Test
    void forbiddenRequestIsAProblem() throws Exception {
        assertProblem(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, bearer()),
                403, "/api/admin/anything");
    }

    @Test
    void unknownResourceIsAProblem() throws Exception {
        String path = "/api/products/" + UUID.randomUUID();
        assertProblem(get(path), 404, path);
    }

    @Test
    void malformedPathVariableIsAProblem() throws Exception {
        assertProblem(get("/api/products/not-a-uuid"), 400, "/api/products/not-a-uuid");
    }

    @Test
    void unreadableBodyIsAProblem() throws Exception {
        assertProblem(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"),
                400, "/auth/login");
    }

    @Test
    void failedValidationIsAProblemThatNamesTheFields() throws Exception {
        MvcResult result = assertProblem(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short"}
                                """),
                400, "/auth/register");

        assertThat(result.getResponse().getContentAsString()).contains("\"errors\"");
    }

    @Test
    void outOfRangeQueryParameterIsAProblem() throws Exception {
        assertProblem(get("/api/products?size=9999"), 400, "/api/products");
    }

    @Test
    void conflictIsAProblem() throws Exception {
        testData.createCustomer("taken@example.com", "correct-horse-battery");

        assertProblem(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"taken@example.com","password":"Password1!x",
                                 "fullName":"Someone Else"}
                                """),
                409, "/auth/register");
    }

    /**
     * Performs the request and asserts the three things that hold for every error in this API.
     *
     * @return the result, for the occasional test that wants to look at a problem-specific member
     */
    private MvcResult assertProblem(MockHttpServletRequestBuilder request, int expectedStatus,
                                    String expectedInstance) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getContentType())
                .isNotNull()
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        String body = result.getResponse().getContentAsString();
        var problem = objectMapper.readTree(body);
        // No "type": Spring omits it while it equals the RFC 7807 default of about:blank, which the
        // spec permits. Errors are told apart by status and title, not by a type URI.
        assertThat(problem.propertyNames()).contains("title", "status", "detail", "instance");
        assertThat(problem.get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(problem.get("instance").asString()).isEqualTo(expectedInstance);
        assertThat(problem.get("title").asString()).isNotBlank();

        // Nothing internal leaks: no exception class, no package name, no stack frame.
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("com.mvp.ecommercebackend")
                .doesNotContain("\tat ");
        return result;
    }

    private String bearer() {
        User customer = testData.createCustomer("plain@example.com", "correct-horse-battery");
        return "Bearer " + tokenService.generateAccessToken(customer);
    }
}
