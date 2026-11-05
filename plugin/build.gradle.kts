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

    implementation("io.github.vaperion.blade:paper:1.1.9")

    // Parquet writer with no Hadoop/Avro tree. zstd-jni enables the writer's
    // ZSTD codec; without it Hardwood falls back to UNCOMPRESSED pages.
    implementation("dev.hardwood:hardwood-core:1.1.0.Beta3")
    implementation("com.github.luben:zstd-jni:1.5.7-3")

    // Uploads to the S3-compatible bucket (Cloudflare R2). Hardwood's s3 module
    // has no write API in any release, so the AWS SDK v2 carries this; the
    // url-connection client avoids pulling in Netty.
    val awssdk = platform("software.amazon.awssdk:bom:2.57.9")
    implementation(awssdk)
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
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
