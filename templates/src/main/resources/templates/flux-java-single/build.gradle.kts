plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_24

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("io.flux-capacitor:flux-capacitor-bom:0.1199.1")
    }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    
    // Flux Capacitor
    implementation("io.flux-capacitor:java-client")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("io.flux-capacitor:java-client")
    
    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.flux-capacitor:java-client") {
        artifact {
            classifier = "tests"
        }
    }
    testImplementation("io.flux-capacitor:test-server")
    testImplementation("io.flux-capacitor:proxy")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("app.jar")
}