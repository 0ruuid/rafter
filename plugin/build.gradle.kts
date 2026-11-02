plugins {
    id("com.github.johnrengelman.shadow")
    id("com.hiusers.klos") version "0.0.2"
}

val packagedProjects = rootProject.subprojects.filter { it.path.startsWith(":project:") }

// 先配置被装配项目，再由 TabooLib 解析依赖。
packagedProjects.forEach { evaluationDependsOn(it.path) }

taboolib {
    // 使用固定 TabooLib 的私有 YAML 命名空间，避免绑定服务器版本。
    relocate("org.yaml.snakeyaml", "org.yaml.snakeyaml_2_6")
    description {
        name(rootProject.name)
        prefix(rootProject.name)
        bukkitApi("1.21.6")
        contributors {
            name("HiUsers")
        }
        links {
            name("homepage").url("https://iplugin.hiusers.com/")
        }
        dependencies {
            name("packetevents")
            name("PlaceholderAPI").optional(true)
            name("Xerr").optional(true)
        }
    }

    classifier = null
}

dependencies {
    packagedProjects.forEach {
        taboo(project(it.path)) { isTransitive = false }
    }

    configurations.implementation.get().dependencies.forEach {
        configurations.taboo.get().dependencies.add(it)
    }
}

tasks {
    jar {
        archiveBaseName.set(rootProject.name)
        archiveVersion.set("")
        // 区分原始 JAR 与最终部署产物。
        archiveClassifier.set("original")
        packagedProjects.forEach {
            from(it.sourceSets["main"].output)
        }
    }

    register<Jar>("sourcesJar") {
        archiveBaseName.set(rootProject.name)
        archiveVersion.set("")
        archiveClassifier.set("sources")

        packagedProjects.forEach { subproject ->
            val sourceSets = subproject.extensions.findByType<SourceSetContainer>()
            sourceSets?.findByName("main")?.let { mainSourceSet ->
                from(mainSourceSet.allSource)
            }
        }
    }

    shadowJar {
        dependsOn(jar)
        archiveBaseName.set(rootProject.name)
        archiveVersion.set("")
        archiveClassifier.set("")

        // 依赖由 TabooLib、Klos 管理，不重复打包。
        dependencies {
            exclude(dependency(".*:.*"))
        }
        from(jar.get().archiveFile)
    }

    build {
        dependsOn(shadowJar)
    }
}
