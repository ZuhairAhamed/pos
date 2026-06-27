package com.company.pos.common.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handle(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.errorCode().status(), ex.getMessage());
        problem.setProperty("code", ex.errorCode().name());
        return problem;
    }
}
