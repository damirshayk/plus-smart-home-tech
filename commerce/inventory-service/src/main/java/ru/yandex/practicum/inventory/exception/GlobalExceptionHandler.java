package ru.yandex.practicum.inventory.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e) {
        log.warn("Ресурс не найден: {}", e.getMessage());
        return new ErrorResponse(HttpStatus.NOT_FOUND.value(), e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInsufficientStock(InsufficientStockException e) {
        log.warn("Недостаточно товара: {}", e.getMessage());
        return new ErrorResponse(HttpStatus.CONFLICT.value(), e.getMessage());
    }

    @ExceptionHandler(InventoryAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleAlreadyExists(InventoryAlreadyExistsException e) {
        log.warn("Складская запись уже существует: {}", e.getMessage());
        return new ErrorResponse(HttpStatus.CONFLICT.value(), e.getMessage());
    }

    /**
     * Конфликт оптимистичной блокировки: два запроса одновременно изменили одну запись.
     * Клиент должен повторить запрос.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Конфликт конкурентного доступа: {}", e.getMessage());
        return new ErrorResponse(HttpStatus.CONFLICT.value(),
                "Конфликт конкурентного доступа. Данные были изменены другим запросом. Повторите операцию.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Некорректный запрос: {}", e.getMessage());
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        log.warn("Ошибка валидации: {}", errors);
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Ошибка валидации", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidRequest() {
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Некорректный формат запроса");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception e) {
        log.error("Внутренняя ошибка сервера", e);
        return new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Внутренняя ошибка сервера");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(e.getHeaders())
                .body(new ErrorResponse(
                        HttpStatus.METHOD_NOT_ALLOWED.value(),
                        "HTTP-метод не поддерживается для этого адреса"
                ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(e.getHeaders())
                .body(new ErrorResponse(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                        "Тип содержимого запроса не поддерживается"
                ));
    }
}
