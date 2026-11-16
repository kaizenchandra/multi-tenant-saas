package com.example.saas;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

class ApiError {
    static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Operation not permitted");
    }

    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }

    static ResponseStatusException conflict(String s) {
        return new ResponseStatusException(HttpStatus.CONFLICT, s);
    }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(
                        ProblemDetail.forStatusAndDetail(
                                e.getStatusCode(), e.getReason() == null ? "Request rejected" : e.getReason()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> constraint() {
        return ResponseEntity.status(409)
                .body(
                        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource constraint conflict"));
    }

    @ExceptionHandler({
            org.springframework.dao.QueryTimeoutException.class,
            org.springframework.dao.PessimisticLockingFailureException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.transaction.TransactionSystemException.class
    })
    ResponseEntity<ProblemDetail> timeout() {
        return ResponseEntity.status(503)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Retry later"));
    }
}
