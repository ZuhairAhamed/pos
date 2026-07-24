package com.company.pos.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handle(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Concurrent update detected; please retry");
        problem.setProperty("code", ErrorCode.CONFLICT.name());
        return problem;
    }
}
