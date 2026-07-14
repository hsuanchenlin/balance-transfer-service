package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error model promises one consistent shape - {timestamp, status, error,
 * message, path} - for every failure, including the ones Spring would otherwise
 * answer with its default body: malformed JSON, unsupported methods, unknown
 * routes.
 */
class ErrorModelIT extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("rawtypes")
    void malformedJsonBody_returns400_inApiErrorShape() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.postForEntity(
                "/transfers", new HttpEntity<>("{not-json", headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertApiErrorShape(response, 400, "/transfers");
        assertThat(response.getBody().get("message")).isEqualTo("Malformed request body");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void unsupportedMethod_returns405_inApiErrorShape() {
        ResponseEntity<Map> response = rest.exchange(
                "/transfers", HttpMethod.DELETE, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertApiErrorShape(response, 405, "/transfers");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void unknownRoute_returns404_inApiErrorShape() {
        var response = rest.getForEntity("/no-such-endpoint", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertApiErrorShape(response, 404, "/no-such-endpoint");
    }

    @SuppressWarnings("rawtypes")
    private static void assertApiErrorShape(ResponseEntity<Map> response, int status, String path) {
        Map body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(status);
        assertThat(body.get("path")).isEqualTo(path);
        assertThat(body.get("timestamp")).isNotNull();
        assertThat(body.get("error")).isNotNull();
        assertThat(body.get("message")).isNotNull();
    }
}
