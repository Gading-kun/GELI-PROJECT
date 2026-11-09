package com.geli.warehouse.common.error;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException exception, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException exception, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException exception, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integrityViolation(DataIntegrityViolationException exception, WebRequest request) {
        String message = "The operation conflicts with an existing record.";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if ("uk_variants_sku".equals(constraint)) {
                    message = "A variant with that SKU already exists.";
                } else if ("uk_variants_options".equals(constraint)) {
                    message = "A variant with that size and color already exists for this item.";
                }
                break;
            }
        }
        return problem(HttpStatus.CONFLICT, "CONFLICT", message, request);
    }

    @ExceptionHandler({ConcurrencyFailureException.class, QueryTimeoutException.class})
    ResponseEntity<Object> resourceBusy(Exception exception, WebRequest request) {
        ProblemDetail body = body(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_BUSY",
                "The requested resource is busy. Please try again.", request, List.of());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(body);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> databaseUnavailable(Exception exception, WebRequest request) {
        log.error("Database operation could not start", exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "The database is temporarily unavailable. Please try again.", request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException exception, WebRequest request) {
        List<ValidationError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ValidationError(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(errorOrder())
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        log.error("Unexpected request failure", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new ValidationError(error instanceof FieldError fieldError
                        ? fieldError.getField() : error.getObjectName(), error.getDefaultMessage()))
                .sorted(errorOrder())
                .toList();
        return validation(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return unexpected(exception, request);
        }
        List<ValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> {
                    if (result instanceof ParameterErrors parameterErrors) {
                        return parameterErrors.getAllErrors().stream()
                                .map(error -> new ValidationError(error instanceof FieldError fieldError
                                        ? fieldError.getField() : error.getObjectName(), error.getDefaultMessage()));
                    }
                    return result.getResolvableErrors().stream()
                            .map(error -> new ValidationError(result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage()));
                })
                .sorted(errorOrder())
                .toList();
        return validation(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or contains invalid JSON, fields, or values.", request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "A request parameter has an invalid value.", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object responseBody,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = switch (status.value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR";
        };
        String detail = switch (status.value()) {
            case 404 -> "The requested endpoint was not found.";
            case 405 -> "This HTTP method is not supported for the requested endpoint.";
            case 406 -> "The requested response format is not supported.";
            case 415 -> "The request content type is not supported. Use application/json.";
            default -> status.is4xxClientError() ? "The request could not be processed."
                    : "An unexpected error occurred. Please try again later.";
        };
        return new ResponseEntity<>(body(resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved,
                code, detail, request, List.of()), headers, status);
    }

    private ResponseEntity<Object> problem(HttpStatus status, String code, String detail, WebRequest request) {
        return ResponseEntity.status(status).body(body(status, code, detail, request, List.of()));
    }

    private ResponseEntity<Object> validation(List<ValidationError> errors, WebRequest request) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed.", request, errors));
    }

    private ProblemDetail body(HttpStatus status, String code, String detail,
            WebRequest request, List<ValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:geli:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        problem.setProperty("code", code);
        problem.setProperty("errors", errors);
        return problem;
    }

    private Comparator<ValidationError> errorOrder() {
        return Comparator.comparing(ValidationError::field, Comparator.nullsFirst(String::compareTo))
                .thenComparing(ValidationError::message, Comparator.nullsFirst(String::compareTo));
    }

    public record ValidationError(String field, String message) {
    }
}
