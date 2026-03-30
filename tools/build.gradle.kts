plugins {
    application
}

dependencies {
    implementation(project(":compiler"))
}

application {
    mainClass.set("nv.tools.MainKt")
}

// Build a fat JAR so the `nv` shell wrapper can invoke it standalone.
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("nv")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "nv.tools.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
    dependsOn(configurations.runtimeClasspath)
}
