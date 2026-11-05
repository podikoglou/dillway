plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.dillway"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("io.github.vaperion.blade:paper:1.1.9")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveFileName = "dillway.jar"
    relocate("me.vaperion.blade", "com.dillway.libs.blade")

    // Paper provides Adventure and the JetBrains annotations at runtime.
    exclude("net/kyori/**")
    exclude("org/jetbrains/annotations/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
