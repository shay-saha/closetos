package com.closetos.platform.infrastructure;

import com.closetos.platform.api.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> validation(Exception exception, HttpServletRequest request) {
        return problem(400, "VALIDATION", "Check the supplied fields and try again.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
        return problem(409, "CONFLICT", "This item changed. Refresh before saving again.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Request failed", exception);
        return problem(500, "INTERNAL", "Something went wrong. Try again shortly.", request);
    }

    private ResponseEntity<ProblemDetail> problem(
            int status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute("requestId"));
        return ResponseEntity.status(status).body(problem);
    }
}
