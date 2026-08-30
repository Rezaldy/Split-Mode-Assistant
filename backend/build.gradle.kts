import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":shared"))
    // Test-only, not part of the shipped plugin; pairs with testFramework(Platform).
    testImplementation("junit:junit:4.13.2")
}
