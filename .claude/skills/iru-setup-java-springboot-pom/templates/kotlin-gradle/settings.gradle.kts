pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "<artifact-id>"

// One include per leaf module in the manifest's `modules:` list, `:`-path mirroring the directory, in this order.
include(":domain")
include(":application")
include(":infrastructure:database:<engine>")
include(":infrastructure:configuration")
include(":infrastructure:clients:<name>")
include(":infrastructure:producers")
include(":infrastructure:metrics")
include(":api:rest-server")
include(":api:grpc-server")
include(":api:graphql-server")
include(":api:consumers")
include(":boot")
include(":coverage")
