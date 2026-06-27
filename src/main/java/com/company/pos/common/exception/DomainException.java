package com.company.pos.common.exception;

public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static DomainException notFound(String message) {
        return new DomainException(ErrorCode.NOT_FOUND, message);
    }

    public static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION, message);
    }

    public static DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }
}
