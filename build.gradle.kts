import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

group = "com.rizkybusiness.ai"
version = "0.15.0"

val intellijPlatformVersion = providers.gradleProperty("intellijPlatformVersion").get()

plugins {
    application
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    // Platform interfaces (ToolWindowFactory, ...) are compiled with JVM default methods. In
    // the compiler's default compatibility mode our implementing classes get bridge methods
    // for every default we don't override, and the Plugin Verifier then reports those
    // bridges as "deprecated/experimental API overridden". No-compatibility mode inherits the
    // defaults instead; nothing consumes this plugin's classes as a library.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(intellijPlatformVersion)

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    pluginVerification {
        ides {
            // Multi-IDE rule (CLAUDE.md): the plugin must install in IDEA, PyCharm and WebStorm.
            create(IntelliJPlatformType.IntellijIdeaUltimate, intellijPlatformVersion)
            create(IntelliJPlatformType.PyCharmProfessional, intellijPlatformVersion)
            create(IntelliJPlatformType.WebStorm, intellijPlatformVersion)
        }
    }
}
