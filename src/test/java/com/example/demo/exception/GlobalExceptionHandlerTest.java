package com.example.demo.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catch-all handler must keep every response in the ApiError shape, even
 * for Spring {@code ErrorResponse} exceptions carrying a status code outside
 * the {@link HttpStatus} enum, and must preserve the headers those exceptions
 * mandate (e.g. Allow on a 405).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/transfers");

    @Test
    void nonEnumStatusCode_fallsBackTo500_inApiErrorShape() {
        var ex = new ResponseStatusException(HttpStatusCode.valueOf(499));

        var response = handler.handleUnexpected(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().path()).isEqualTo("/transfers");
    }

    @Test
    void methodNotAllowed_keepsAllowHeader() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        var response = handler.handleUnexpected(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(405);
    }
}
