dependencies {
    testImplementation(project(":compiler"))
    testImplementation(project(":tools"))
    testImplementation(libs.junit.jupiter.api)
}

tasks.test {
    // Pass the project root directory as a system property so GoldenFileTest can
    // locate tests/golden/ and tools/build/libs/nv.jar regardless of Gradle's cwd.
    systemProperty("projectDir", rootProject.projectDir.absolutePath)
}
