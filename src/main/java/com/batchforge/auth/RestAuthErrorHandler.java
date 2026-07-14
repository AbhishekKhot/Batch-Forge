package com.batchforge.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class RestAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource");
    }

    private void write(HttpServletResponse response, HttpStatus status, String errorCode, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","errorCode":"%s","timestamp":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, errorCode, Instant.now()));
    }
}