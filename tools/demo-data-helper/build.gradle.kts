plugins { id("com.android.application") version "9.3.2" }
android {
    namespace = "io.github.puvon.enetrend.demodata"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.puvon.enetrend.demodata"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
