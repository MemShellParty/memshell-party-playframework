plugins {
    id("application")
}

description = "Play 2.8 range (Akka HTTP) for the playframework memshell integration test"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

sourceSets.main {
    java.srcDir("../range-common/src/main/java")
    resources.srcDir("../range-common/src/main/resources")
}

dependencies {
    implementation(libs.play2.core)
    implementation(libs.play2.java)
    implementation(libs.play2.server)
    runtimeOnly(libs.play2.akka.http.server)
    runtimeOnly(libs.play2.logback)
}

application {
    mainClass.set("com.reajason.javaweb.playframework.range.PlayframeworkRangeApplication")
}
