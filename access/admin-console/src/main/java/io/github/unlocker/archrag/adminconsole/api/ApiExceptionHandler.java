package io.github.unlocker.archrag.adminconsole.api;

import io.github.unlocker.archrag.graphquerycore.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Ошибки API как JSON {@code {error, message}}; введённые значения в ответ не эхоятся. */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * Тело ошибки.
   *
   * @param error машинный код
   * @param message короткое описание без пользовательского ввода
   */
  public record ApiError(String error, String message) {}

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ApiError> invalid(InvalidRequestException e) {
    return ResponseEntity.badRequest().body(new ApiError("invalid_request", e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
  ResponseEntity<ApiError> malformed(Exception e) {
    return ResponseEntity.badRequest().body(new ApiError("invalid_request", "malformed or missing request parameter"));
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiError> notFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", e.getMessage()));
  }

  @ExceptionHandler(QueryTimeoutException.class)
  ResponseEntity<ApiError> timeout(QueryTimeoutException e) {
    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new ApiError("query_timeout", "graph query timed out"));
  }
}
