package com.closetos.platform.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

final class SecurityProblem {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SecurityProblem() {}

    static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JSON.writeValue(
                response.getOutputStream(),
                Map.of(
                        "type",
                        "about:blank",
                        "title",
                        code,
                        "status",
                        status,
                        "detail",
                        detail,
                        "instance",
                        request.getRequestURI(),
                        "code",
                        code,
                        "requestId",
                        String.valueOf(request.getAttribute("requestId"))));
    }
}
