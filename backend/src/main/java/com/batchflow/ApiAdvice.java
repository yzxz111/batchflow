package com.batchflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return switch (exception.status().value()) {
            case 400 -> error(HttpStatus.BAD_REQUEST, messageOrDefault(exception.getMessage(), "请求无效"), "INVALID_REQUEST");
            case 404 -> error(HttpStatus.NOT_FOUND, messageOrDefault(exception.getMessage(), "请求的资源不存在"), "NOT_FOUND");
            case 409 -> error(HttpStatus.CONFLICT, messageOrDefault(exception.getMessage(), "请求与当前资源状态冲突"), "CONFLICT");
            default -> {
                log.error("Unexpected API exception status: {}", exception.status(), exception);
                yield internalError();
            }
        };
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大，最大支持 50 MiB", "PAYLOAD_TOO_LARGE");
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MultipartException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数或请求体无效，请检查后重试", "INVALID_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
            String message = switch (status.value()) {
                case 404 -> "请求的资源不存在";
                case 405 -> "不支持该请求方法";
                case 415 -> "不支持的请求内容类型";
                default -> status.is4xxClientError() ? "请求无效，请检查后重试" : "服务器内部错误，请稍后重试";
            };
            return ResponseEntity.status(status).body(new ApiError(message, code));
        }
        log.error("Unhandled API exception", exception);
        return internalError();
    }

    private static ResponseEntity<ApiError> internalError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试", "INTERNAL_ERROR");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status).body(new ApiError(message, code));
    }

    private static String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    public record ApiError(String message, String code) {}
}
