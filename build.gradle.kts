plugins {
    java
}

group = "dev.ledge"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.destroystokyo.paper:paper-api:1.13.2-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.1.0")
    annotationProcessor("com.pkware.jabel:jabel-javac-plugin:1.0.1-2")
    compileOnly("com.pkware.jabel:jabel-javac-plugin:1.0.1-2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Source uses Java 17 syntax; Jabel rewrites it so the jar loads on Java 8 (the era's usual server Java).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("paper-1.13.2")
}
