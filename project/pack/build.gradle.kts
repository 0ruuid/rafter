dependencies {
    implementation(project(":project:core"))
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")
    // 复用宿主固定的私有 YAML 命名空间。
    compileOnly("org.yaml:snakeyaml:2.6")
    testImplementation("org.yaml:snakeyaml:2.6")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

taboolib { subproject = true }

// 测试复用宿主主题与字体，不构成生产反向依赖。
tasks.processTestResources {
    from(project(":project:bukkit").layout.projectDirectory.dir("src/main/resources")) {
        include("core/ui/theme.yml", "core/ui/fonts/**")
    }
}
