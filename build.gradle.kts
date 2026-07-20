plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "ru.shakhed"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val resourcePack = tasks.register<Zip>("resourcePack") {
    from("resource-pack") {
        exclude("source/**")
    }
    archiveFileName.set("ShakhedDrones-resourcepack-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.build {
    dependsOn("resourcePack")
}

tasks.jar {
    dependsOn(resourcePack)
    from(resourcePack.flatMap { it.archiveFile }) {
        rename { "shakheddrones-resourcepack.zip" }
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
    runDirectory.set(layout.projectDirectory.dir("run"))
}
