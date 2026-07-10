package com.company.pos.terminal.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final transient ProblemDetail problem;

    public ApiException(int status, ProblemDetail problem, String message) {
        super(message);
        this.status = status;
        this.problem = problem;
    }

    public int status() { return status; }
    public ProblemDetail problem() { return problem; }
}
