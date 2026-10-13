package me.cortex.voxy.commonImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxyCommonTest {

    @AfterEach
    void cleanProperties() {
        System.clearProperty("voxy.testFlag");
        System.clearProperty("voxy.defaultedFlag");
    }

    @Test
    void verificationFlagDefaultsToFalse() {
        assertFalse(VoxyCommon.isVerificationFlagOn("nonexistentFlag"));
    }

    @Test
    void verificationFlagRespectsExplicitDefault() {
        assertTrue(VoxyCommon.isVerificationFlagOn("nonexistentFlag", true));
        assertFalse(VoxyCommon.isVerificationFlagOn("nonexistentFlag", false));
    }

    @Test
    void verificationFlagReadsSystemProperty() {
        System.setProperty("voxy.testFlag", "true");
        assertTrue(VoxyCommon.isVerificationFlagOn("testFlag"));
        System.setProperty("voxy.testFlag", "false");
        assertFalse(VoxyCommon.isVerificationFlagOn("testFlag"));
    }

    @Test
    void verificationFlagSystemPropertyOverridesDefault() {
        // Property set to false → false even if default is true.
        System.setProperty("voxy.defaultedFlag", "false");
        assertFalse(VoxyCommon.isVerificationFlagOn("defaultedFlag", true));
        // Property set to true → true even if default is false.
        System.setProperty("voxy.defaultedFlag", "true");
        assertTrue(VoxyCommon.isVerificationFlagOn("defaultedFlag", false));
    }

    @Test
    void verificationFlagOnlyAcceptsLiteralTrue() {
        // Anything other than the exact string "true" is treated as false.
        System.setProperty("voxy.testFlag", "TRUE");
        assertFalse(VoxyCommon.isVerificationFlagOn("testFlag"),
                "case-sensitive: only literal lowercase \"true\" enables");
        System.setProperty("voxy.testFlag", "1");
        assertFalse(VoxyCommon.isVerificationFlagOn("testFlag"));
        System.setProperty("voxy.testFlag", "yes");
        assertFalse(VoxyCommon.isVerificationFlagOn("testFlag"));
    }

    @Test
    void isAvailableFalseWithoutFactory() {
        // Tests run before any factory is set; expect not available.
        // Note: this is order-sensitive across the suite, but no other test
        // in the project sets a factory.
        assertFalse(VoxyCommon.isAvailable());
    }

    @Test
    void breakpointIsCallableNoOp() {
        // Just verify it doesn't throw.
        VoxyCommon.breakpoint();
    }
}
