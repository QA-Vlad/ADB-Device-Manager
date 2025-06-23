// Файл: build.gradle.kts

import java.util.Properties
import java.io.FileInputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "io.github.qavlad"
version = "0.1.0-SNAPSHOT"

// Загружаем локальные properties если они есть
val localPropertiesFile = rootProject.file("gradle-local.properties")
if (localPropertiesFile.exists()) {
    val localProperties = Properties()
    FileInputStream(localPropertiesFile).use { localProperties.load(it) }
    localProperties.forEach { key, value ->
        project.ext[key.toString()] = value
    }
}

repositories {
    mavenCentral()
    // Добавляем репозиторий Google, где лежат все Android-библиотеки, включая ddmlib
    google()
}

intellij {
    version.set("2023.2.5")
    type.set("IC")
    plugins.set(listOf("org.jetbrains.android"))

    // Позволяет локально переопределить путь к sandbox
    sandboxDir.set(project.findProperty("intellij.sandboxDir") as String? ?: "build/idea-sandbox")
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
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

    patchPluginXml {
        changeNotes.set("""
            <b>v0.1.0</b><br>
            <ul>
                <li>Initial release.</li>
                <li>Added a tool window with a 'Reset Screen' button.</li>
            </ul>
        """.trimIndent())

        sinceBuild.set("232")
        untilBuild.set("242.*")
    }
}