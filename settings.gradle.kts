pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Released shelltools and third-party dependencies.
        mavenCentral {
            mavenContent { releasesOnly() }
        }
        // The 3.2.0-SNAPSHOT host SDK is published here. Keeping the same repository locally
        // and in CI avoids resolving a different snapshot from a developer's mavenLocal.
        maven("https://central.sonatype.com/repository/maven-snapshots") {
            name = "memshellSnapshots"
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "memshell-party-playframework"

include("plugin")
include("range-playframework2")
include("range-playframework3")
include("integration-test")
