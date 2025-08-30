    // Файл: build.gradle.kts

import java.util.Properties
import java.io.FileInputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "io.github.qavlad"
version = "1.0"

// Загружаем локальные properties если они есть
val localPropertiesFile = rootProject.file("gradle-local.properties")
if (localPropertiesFile.exists()) {
    val localProperties = Properties()
    FileInputStream(localPropertiesFile).use { localProperties.load(it) }
    localProperties.forEach { (key, value) ->
        project.ext[key.toString()] = value
    }
}

repositories {
    mavenCentral()
    // Добавляем репозиторий Google, где лежат все Android-библиотеки, включая ddmlib
    google()
}

intellij {
    version.set("2023.2")
    type.set("IC")
    plugins.set(listOf())

    // Позволяет локально переопределить путь к sandbox
    sandboxDir.set(project.findProperty("intellij.sandboxDir") as String? ?: "build/idea-sandbox")
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Android ddmlib для работы с ADB
    implementation("com.android.tools.ddms:ddmlib:31.3.2")
    implementation("com.android.tools:sdk-common:31.3.2")
    
    // Sentry для отслеживания крашей
    implementation("io.sentry:sentry:7.3.0")
    
    // Тестовые зависимости
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("io.mockk:mockk:1.13.7")

    // Kotlin Coroutines уже включены в IntelliJ Platform, не нужно добавлять явно
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
    
    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    patchPluginXml {
        changeNotes.set("""
            <b>v0.1.0</b><br>
            <ul>
                <li>Initial release.</li>
                <li>Added a tool window with a 'Reset Screen' button.</li>
            </ul>
        """.trimIndent())

        sinceBuild.set("223")
        untilBuild.set("")  // Пустая строка означает отсутствие ограничения максимальной версии
    }
}