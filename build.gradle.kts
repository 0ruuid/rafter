import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("io.izzel.taboolib") version "2.0.39"
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    kotlin("jvm") version "2.1.0"
}

subprojects {
    // 聚合项目不参与编译和装配。
    if (path == ":project" || path == ":tools") {
        return@subprojects
    }

    apply<JavaPlugin>()
    apply(plugin = "io.izzel.taboolib")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // 仅宿主安装平台能力，沿用现有 Klos 装配。
    taboolib {
        if (path == ":plugin" || path == ":project:bukkit") {
            env {
                install(Basic, Bukkit, BukkitUtil, BukkitHook, CommandHelper, I18n, Kether)
            }
        }
        version {
            taboolib = "6.3.0-0e3a911"
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.tabooproject.org/repository/releases")
        maven("https://repo.hiusers.com/releases")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.set(listOf("-Xjvm-default=all", "-Xextended-compiler-checks"))
        }
    }

    tasks.test {
        useJUnitPlatform()
        maxHeapSize = "256m"
        maxParallelForks = 1
        jvmArgs("-XX:ActiveProcessorCount=2")
    }
}
