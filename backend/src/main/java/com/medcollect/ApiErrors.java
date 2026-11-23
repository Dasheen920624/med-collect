package com.medcollect;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> workflow(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                DocumentReader.withDefault(error.getReason(), "请求未完成")));
    }

    @ExceptionHandler(CdssClient.RemoteFailure.class)
    ResponseEntity<Map<String, String>> cdss(CdssClient.RemoteFailure error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(Map.of("message", field == null ? "请核对输入内容"
                : "请核对输入字段 " + field.getField() + "（" + field.getDefaultMessage() + "）"));
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class, IOException.class})
    ResponseEntity<Map<String, String>> input(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message", "请核对输入内容或原件文件"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> uploadSize() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "原件不能超过 20 MB"));
    }
}
