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
    // Paper has no 1.8.9 server; the 1.8.8 server jar (Paper cache/patched_1.8.8.jar, git-ignored) provides the Bukkit API.
    compileOnly(files("libs/server-1.8.8.jar"))
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
    archiveClassifier.set("paper-1.8.9")
}
