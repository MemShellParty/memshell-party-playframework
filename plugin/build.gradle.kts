plugins {
    `java-library`
    alias(libs.plugins.lombok)
}

description = "PlayFramework server provider plugin for memshell-party"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    // Host-provided at runtime. Bundling these into the plugin would duplicate class identities
    // and put a second copy of the SPI providers on the host classpath.
    compileOnly("io.github.reajason:memshell-party-api:3.2.0-SNAPSHOT")
    compileOnly("io.github.reajason:memshell-party-core:3.2.0-SNAPSHOT")
    compileOnly("org.kohsuke.metainf-services:metainf-services:1.11")
    annotationProcessor("org.kohsuke.metainf-services:metainf-services:1.11")
    // The injector template reflects over Akka/Pekko HTTP types only; nothing is compiled
    // against them. No runtime dependency is needed inside the plugin jar.

    testImplementation("io.github.reajason:memshell-party-api:3.2.0-SNAPSHOT")
    testImplementation("io.github.reajason:memshell-party-core:3.2.0-SNAPSHOT")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

base {
    archivesName.set("memshell-party-playframework-plugin")
}

tasks.processResources {
    val resourceVersion = project.version.toString()
    inputs.property("pluginVersion", resourceVersion)
    filesMatching("META-INF/memshell-party-plugin.properties") {
        expand("version" to resourceVersion)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "memshell-party-playframework-plugin",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "memshell-party",
        )
    }
}
