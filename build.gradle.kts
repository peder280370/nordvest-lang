plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "implementation"(rootProject.libs.kotlin.stdlib)
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.jupiter.engine)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
