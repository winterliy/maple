plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
    `maven-publish`
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // Ship the Fabric API modules this mod uses inside the jar (jar-in-jar): players only need Fabric Loader.
    for (module in listOf("fabric-api-base", "fabric-command-api-v2", "fabric-events-interaction-v0", "fabric-lifecycle-events-v1", "fabric-networking-api-v1")) {
        include(fabricApi.module(module, property("fabric_api_version").toString()))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

val minecraftVersion = property("minecraft_version") as String
tasks.jar {
    archiveClassifier.set("fabric-$minecraftVersion")
}

