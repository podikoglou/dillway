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
    // Read SNAPPY pages too. The legacy JSON converter used pyarrow's default
    // codec, so its older files are SNAPPY-compressed.
    implementation("org.xerial.snappy:snappy-java:1.1.10.8")

    // Uploads to the S3-compatible bucket (Cloudflare R2). Hardwood's s3 module
    // has no write API in any release, so the AWS SDK v2 carries this; the
    // url-connection client avoids pulling in Netty.
    val awssdk = platform("software.amazon.awssdk:bom:2.57.9")
    implementation(awssdk)
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    // Local inference for downloaded ONNX specialists. The jar carries
    // natives for every platform; the shadow task keeps only Linux x64.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")

    // Reads the R2 latest pointer and the model's dw.* metadata.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
}

tasks.shadowJar {
    archiveFileName = "dw-agent.jar"
    relocate("me.vaperion.blade", "com.dillway.agent.libs.blade")
    relocate("com.google.gson", "com.dillway.agent.libs.gson")

    // Paper provides Adventure and the JetBrains annotations at runtime.
    exclude("net/kyori/**")
    exclude("org/jetbrains/annotations/**")

    // ai.onnxruntime must keep its package: its JNI entry points and native
    // loader use the original names. The server is Linux x64, so drop the
    // other platform natives (the dependency is ~140 MB without this).
    exclude("ai/onnxruntime/native/linux-aarch64/**")
    exclude("ai/onnxruntime/native/osx-aarch64/**")
    exclude("ai/onnxruntime/native/osx-x64/**")
    exclude("ai/onnxruntime/native/win-x64/**")

    // kotlin-stdlib ships in the jar; it must stay in the kotlin.* packages.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
