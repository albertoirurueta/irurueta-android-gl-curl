// Applied to `boot` only: the executable jar (bootJar), bootRun and bootBuildImage.
plugins {
    id("iru.kotlin-library")
    id("org.springframework.boot")
}

val libs = versionCatalogs.named("libs")

dependencies {
    // developmentOnly (spring-boot-docker-compose) doesn't extend implementation, so it needs the BOM too.
    "developmentOnly"(platform(libs.findLibrary("spring-boot-dependencies").get()))
    implementation(libs.findLibrary("jackson-module-kotlin").get())
}
