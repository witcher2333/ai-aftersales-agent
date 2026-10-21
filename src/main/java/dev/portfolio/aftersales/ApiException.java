package dev.portfolio.aftersales;

import org.springframework.http.HttpStatus;

class ApiException extends RuntimeException {
    private final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() { return status; }
}
