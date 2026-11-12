plugins {
    id("application")
}

description = "Play 3.0 range (Pekko HTTP) for the playframework memshell integration test"

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
    implementation(libs.play3.core)
    implementation(libs.play3.java)
    implementation(libs.play3.server)
    runtimeOnly(libs.play3.pekko.http.server)
    runtimeOnly(libs.play3.logback)
}

application {
    mainClass.set("com.reajason.javaweb.playframework.range.PlayframeworkRangeApplication")
}
