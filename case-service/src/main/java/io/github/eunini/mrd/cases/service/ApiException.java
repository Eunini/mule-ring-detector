package io.github.eunini.mrd.cases.service;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Business error translated to an RFC 7807 problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> errors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<String> errors) {
        super(message);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> errors() {
        return errors;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException badRequest(String message, List<String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, errors);
    }
}
