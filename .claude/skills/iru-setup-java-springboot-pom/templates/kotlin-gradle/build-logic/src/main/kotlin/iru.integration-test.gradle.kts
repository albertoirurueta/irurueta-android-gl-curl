// *Test classes run in `test`; *IT classes (Testcontainers-backed, slower) run in `integrationTest`, which `check`
// depends on. Both share the test source set, mirroring Surefire/Failsafe on the Maven side.
plugins {
    java
}

tasks.named<Test>("test") {
    exclude("**/*IT*")
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs the *IT integration tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        include("**/*IT*")
        // Most modules have unit tests but no *IT; Gradle 9 would otherwise fail on "no tests discovered".
        failOnNoDiscoveredTests = false
        shouldRunAfter(tasks.named("test"))
    }

tasks.named("check") {
    dependsOn(integrationTest)
}
