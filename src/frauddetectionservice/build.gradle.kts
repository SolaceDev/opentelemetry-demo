
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "1.9.10"
    application
    id("java")
    id("idea")
    id("com.google.protobuf") version "0.9.4"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "io.opentelemetry"
version = "1.0"


val grpcVersion = "1.58.0"
val protobufVersion = "3.24.4"


repositories {
    mavenCentral()
    gradlePluginPortal()
}



dependencies {
    implementation("com.google.protobuf:protobuf-java:${protobufVersion}")
    testImplementation(kotlin("test"))
    implementation(kotlin("script-runtime"))
    implementation("org.apache.kafka:kafka-clients:3.6.0")
    implementation("com.google.api.grpc:proto-google-common-protos:2.26.0")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
    implementation("io.grpc:grpc-netty:${grpcVersion}")
    implementation("io.grpc:grpc-services:${grpcVersion}")
    implementation("io.opentelemetry:opentelemetry-api:1.31.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.31.0")
    implementation("io.opentelemetry:opentelemetry-extension-annotations:1.18.0")
    implementation("org.apache.logging.log4j:log4j-core:2.21.0")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("com.google.protobuf:protobuf-kotlin:${protobufVersion}")
    implementation("com.solacesystems:sol-jms:10.19.0")
    implementation("javax.jms:javax.jms-api:2.0.1")

    if (JavaVersion.current().isJava9Compatible) {
        // Workaround for @javax.annotation.Generated
        // see: https://github.com/grpc/grpc-java/issues/3633
        implementation("javax.annotation:javax.annotation-api:1.3.2")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}"
    }
    plugins {

        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
        }
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.plugins {
                // Apply the "grpc" plugin whose spec is defined above, without
                // options. Note the braces cannot be omitted, otherwise the
                // plugin will not be added. This is because of the implicit way
                // NamedDomainObjectContainer binds the methods.
                id("grpc") { }
            }
        }
    }
}

application {
    mainClass.set("frauddetectionservice.MainKt")
}

tasks.jar {
    manifest.attributes["Main-Class"] = "frauddetectionservice.MainKt"
}
