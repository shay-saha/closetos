package com.closetos;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundariesTest {
    @Test
    void modulesOnlyUseDeclaredInterfaces() {
        ApplicationModules.of(ClosetOsApplication.class).verify();
    }
}
