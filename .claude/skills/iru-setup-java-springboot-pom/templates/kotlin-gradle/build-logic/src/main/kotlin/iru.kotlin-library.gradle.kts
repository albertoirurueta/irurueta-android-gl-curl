// Every Kotlin module: Kotlin/JVM + the Spring (all-open) and JPA (no-arg) compiler plugins, the JDK toolchain,
// the Spring Boot BOM, the shared test stack, and the quality / integration-test / coverage conventions.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("iru.quality")
    id("iru.integration-test")
    id("iru.coverage")
}

val libs = versionCatalogs.named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        javaParameters = true
    }
}

// kotlin-jpa only adds no-arg constructors; Hibernate's lazy proxies also need the entities open.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    implementation(platform(lib("spring-boot-dependencies")))
    // One line per other BOM in the catalog (spring-cloud-dependencies, spring-ai-bom, ...); omit when absent.
    implementation(platform(lib("spring-cloud-dependencies")))
    implementation(lib("kotlin-reflect"))
    // Only when stack.concurrency is reactive or both:
    implementation(lib("kotlinx-coroutines-core"))
    testImplementation(lib("kotlinx-coroutines-test"))

    testImplementation(lib("spring-boot-starter-test"))
    testImplementation(lib("kotlin-test-junit5"))
    testImplementation(lib("mockk"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
