package com.company.pos.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsNotFoundToProblemDetail404() {
        ProblemDetail pd = handler.handle(DomainException.notFound("Sale 42 not found"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getDetail()).isEqualTo("Sale 42 not found");
        assertThat(pd.getProperties()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void mapsValidationToProblemDetail400() {
        ProblemDetail pd = handler.handle(DomainException.validation("Quantity must be positive"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("code", "VALIDATION");
    }
}
