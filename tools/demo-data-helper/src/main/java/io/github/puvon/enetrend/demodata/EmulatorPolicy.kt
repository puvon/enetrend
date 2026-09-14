package io.github.puvon.enetrend.demodata

/** An allowlist for standard Android SDK AVDs. Unknown devices fail closed. */
internal object EmulatorPolicy {
    fun allows(debuggable: Boolean, hardware: String, model: String, fingerprint: String,
        kernelQemu: String, bootQemu: String): Boolean =
        debuggable && (kernelQemu == "1" || bootQemu == "1") &&
            hardware in setOf("ranchu", "goldfish") &&
            (model.startsWith("sdk") || model.startsWith("Android SDK built for")) &&
            (fingerprint.contains("/sdk") || fingerprint.startsWith("generic/") ||
                fingerprint.startsWith("generic_x86/"))
}
