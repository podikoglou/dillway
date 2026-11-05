plugins {
    kotlin("jvm") version "2.4.21"
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
    compileOnly("com.google.code.gson:gson:2.11.0") // Paper provides Gson at runtime

    implementation("io.github.vaperion.blade:paper:1.1.9")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

// The Kotlin plugin doesn't feed .java files in src/main/kotlin to javac.
sourceSets.main {
    java.srcDirs("src/main/kotlin")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnit()
}

tasks.shadowJar {
    archiveFileName = "dillway.jar"
    relocate("me.vaperion.blade", "com.dillway.libs.blade")

    // Paper provides Adventure and the JetBrains annotations at runtime.
    exclude("net/kyori/**")
    exclude("org/jetbrains/annotations/**")

    // kotlin-stdlib ships in the jar; it must stay in the kotlin.* packages.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
