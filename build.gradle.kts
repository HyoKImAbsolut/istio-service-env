import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.11.4"
}

group = "com.zaeyi"
version = project.findProperty("version")?.toString() ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
    implementation("io.javaoperatorsdk:operator-framework-spring-boot-starter:6.3.3")
    
    // Kubernetes Client
    implementation("io.fabric8:kubernetes-client:7.5.2")
    
    // Istio Model (fabric8 kubernetes-model for Istio)
    implementation("io.fabric8:istio-model:7.5.2")
    
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