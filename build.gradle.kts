// Файл: build.gradle.kts

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "io.github.qavlad"
version = "0.1.0-SNAPSHOT"

// --- ИЗМЕНЕНИЕ ЗДЕСЬ ---
repositories {
    mavenCentral()
    // Добавляем репозиторий Google, где лежат все Android-библиотеки, включая ddmlib
    google()
}

// Блок intellij остается без изменений
intellij {
    version.set("2023.2.5")
    type.set("IC")
    plugins.set(listOf("org.jetbrains.android"))
}

// Блок зависимостей остается без изменений
dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

// Блок tasks остается без изменений
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