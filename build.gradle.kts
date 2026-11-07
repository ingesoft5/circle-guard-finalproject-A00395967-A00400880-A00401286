plugins {
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
    id("org.sonarqube") version "5.1.0.4882"
}

// Servicio a analizar con SonarQube: ./gradlew sonar -PsonarService=<servicio>
// Sin la propiedad se analizan todos los servicios como un solo proyecto.
val sonarService = findProperty("sonarService") as String?

allprojects {
    group = "com.circleguard"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("com.h2database:h2")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // --- CI/CD (Jenkins) ---------------------------------------------------
    // Pruebas de integración: las marcadas con @Tag("integration") (usan
    // Testcontainers y necesitan Docker). 'test' las excluye para que las
    // unitarias sigan siendo rápidas; 'integrationTest' corre solo esas.
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("integration") }
    }
    val testSourceSet = the<SourceSetContainer>()["test"]
    tasks.register<Test>("integrationTest") {
        description = "Pruebas de integración entre servicios (@Tag(\"integration\"))."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        shouldRunAfter(tasks.named("test"))
    }

    // JaCoCo: reporte XML que publica Jenkins (Coverage) y que lee SonarQube.
    apply(plugin = "jacoco")
    // Exclusiones de cobertura (CG-35), documentadas en docs/pruebas/cobertura.md:
    // clase main de Spring Boot, configuración (beans y seguridad), DTO y
    // entidades/modelos. No tienen lógica de negocio propia; la lógica vive en
    // servicios, controladores, listeners y filtros, que sí se miden.
    val coverageExclusions = listOf(
        "**/*Application*", "**/config/**", "**/dto/**", "**/model/**",
    )
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        classDirectories.setFrom(files(classDirectories.files.map {
            fileTree(it) { exclude(coverageExclusions) }
        }))
    }

    // Meta de E1.6 impuesta por el build y no solo reportada: en los 5 servicios
    // núcleo, 'check' falla si la cobertura de líneas baja de 60 %.
    val coreServices = setOf(
        "circleguard-auth-service", "circleguard-identity-service", "circleguard-promotion-service",
        "circleguard-form-service", "circleguard-gateway-service",
    )
    if (project.name in coreServices) {
        val verify = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("jacocoTestReport"))
            classDirectories.setFrom(files(classDirectories.files.map {
                fileTree(it) { exclude(coverageExclusions) }
            }))
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.60".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") { dependsOn(verify) }
    }

    // SonarQube: un proyecto por servicio. El pipeline pasa -PsonarService y
    // -Dsonar.projectKey=circleguard-<servicio>; los demás módulos se omiten.
    if (sonarService != null) {
        extensions.configure<org.sonarqube.gradle.SonarExtension>("sonar") {
            isSkipProject = project.name != sonarService
        }
    }
}
