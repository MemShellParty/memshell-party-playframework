plugins {
    id("java")
}

description = "End-to-end tests: plugin discovery, injection, and shell connectivity against Play ranges"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

evaluationDependsOn(":range-playframework2")
evaluationDependsOn(":range-playframework3")

val pluginsDir = layout.buildDirectory.dir("plugins")
// CI verifies the uploaded artifact by feeding the downloaded jar back into the suite.
val externalPluginJar = providers.gradleProperty("pluginJar")

dependencies {
    testImplementation("io.github.reajason:memshell-party-api:3.2.0-SNAPSHOT")
    testImplementation("io.github.reajason:memshell-party-core:3.2.0-SNAPSHOT")
    // The built-in tools come from the released shelltool modules on Maven Central.
    testImplementation("io.github.reajason:memshell-party-shelltool-godzilla:3.0.0")
    testImplementation("io.github.reajason:memshell-party-shelltool-command:3.1.0")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val preparePlugins = tasks.register<Sync>("preparePlugins") {
    if (externalPluginJar.isPresent) {
        val jar = file(externalPluginJar.get())
        require(jar.isFile) { "pluginJar does not exist: $jar" }
        from(jar)
    } else {
        from(project(":plugin").tasks.named("jar"))
    }
    into(pluginsDir)
}

tasks.test {
    useJUnitPlatform()
    // The plugin jar is loaded at runtime, not from the test classpath; it still has to
    // invalidate the cached test result when it changes.
    inputs.dir(pluginsDir)
    dependsOn(preparePlugins, ":range-playframework2:classes", ":range-playframework3:classes")
    doFirst {
        systemProperty("memshell.plugins.dir", pluginsDir.get().asFile.absolutePath)
        systemProperty(
            "playframework.range2.classpath",
            project(":range-playframework2").extensions.getByType<SourceSetContainer>()["main"]
                .runtimeClasspath.asPath,
        )
        systemProperty(
            "playframework.range3.classpath",
            project(":range-playframework3").extensions.getByType<SourceSetContainer>()["main"]
                .runtimeClasspath.asPath,
        )
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
