package com.closetos.privacy.api;

import com.closetos.privacy.application.PersonalDataExport;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PersonalDataController {
    private final PersonalDataExport exports;

    PersonalDataController(PersonalDataExport exports) {
        this.exports = exports;
    }

    @GetMapping("/api/v1/me/data")
    void download(HttpServletResponse response) throws IOException {
        exports.download(response);
    }
}
