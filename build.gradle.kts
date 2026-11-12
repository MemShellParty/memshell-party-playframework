allprojects {
    group = "io.github.reajason"
    version = "3.2.0-SNAPSHOT"

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
