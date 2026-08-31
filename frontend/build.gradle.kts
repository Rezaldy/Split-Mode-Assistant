import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        testFramework(TestFrameworkType.Platform)

        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }

    implementation(project(":shared"))
    // Test-only, not part of the shipped plugin; pairs with testFramework(Platform).
    testImplementation("junit:junit:4.13.2")
}
