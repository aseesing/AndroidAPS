import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    // On change edit aaps-ci.yml
    // PERSONAL PATCH: upstream dev says "4.0.1-dev-a". Without "-dev" AAPS does not see a dev build,
    // so the closed loop works without engineering mode (see .github/README.md, base and branches).
    const val appVersion = "4.0.1"
    const val versionCode = 1500

    const val compileSdk = 37
    const val minSdk = 31
    const val targetSdk = 35
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
