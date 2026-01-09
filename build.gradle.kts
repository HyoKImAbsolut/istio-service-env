import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
	id("org.springframework.boot") version "4.0.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.11.3"
}

group = "com.zaeyi"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    
    // Java Operator SDK
    implementation("io.javaoperatorsdk:operator-framework-spring-boot-starter:6.3.1")
    
    // Kubernetes Client
    implementation("io.fabric8:kubernetes-client:6.13.4")
    
    // Istio Model (fabric8 kubernetes-model for Istio)
    implementation("io.fabric8:istio-model-v1beta1:6.13.4")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    // Jackson for JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    
    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("${project.name}-${project.version}-plain.jar")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("serviceenv-operator")
            mainClass.set("com.zaeyi.serviceenv.OperatorApplication")
            buildArgs.add("--verbose")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
