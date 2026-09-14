package io.github.puvon.enetrend.demodata

import org.junit.Assert.*
import org.junit.Test

class EmulatorPolicyTest {
    private fun allowed(debug: Boolean = true, hardware: String = "ranchu",
        model: String = "sdk_gphone64_x86_64", fingerprint: String = "google/sdk_gphone64_x86_64/emu64xa:14",
        kernel: String = "1", boot: String = "1") = EmulatorPolicy.allows(debug, hardware, model, fingerprint, kernel, boot)
    @Test fun permitsStandardAvd() { assertTrue(allowed()) }
    @Test fun acceptsBootQemuWhenKernelPropertyIsAbsent() { assertTrue(allowed(kernel = "")) }
    @Test fun blocksPhysicalPixelEvenIfDebuggable() {
        assertFalse(allowed(hardware = "tensor", model = "Pixel 8", fingerprint = "google/shiba/shiba:14", kernel = "", boot = ""))
    }
    @Test fun blocksMissingQemuEvidence() { assertFalse(allowed(kernel = "", boot = "")) }
    @Test fun blocksQemuFlagAloneOnPhysicalHardware() { assertFalse(allowed(hardware = "qcom")) }
    @Test fun blocksUnrecognizedModel() { assertFalse(allowed(model = "Pixel 8")) }
    @Test fun blocksUnrecognizedFingerprint() { assertFalse(allowed(fingerprint = "unknown")) }
    @Test fun blocksReleaseBuild() { assertFalse(allowed(debug = false)) }
}
