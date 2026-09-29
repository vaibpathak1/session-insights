package io.sessioninsights.api.sessions;

import io.sessioninsights.api.sessions.Dtos.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Minimal JSON errors for the session API; no internal detail, nothing from the request logged. */
@RestControllerAdvice(assignableTypes = SessionController.class)
class ApiExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", null));
    }

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ApiError> invalid(InvalidParameterException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_parameter", e.parameter()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_parameter", e.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missing(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_parameter", e.getParameterName()));
    }
}
