dependencies {
    implementation(project(":project:core"))
    implementation(project(":project:pack"))
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.yaml:snakeyaml:2.6")
    compileOnly("io.izzel.taboolib:common-env:6.3.0-0e3a911")
    compileOnly("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation("io.izzel.taboolib:common-util:6.3.0-0e3a911")
    testImplementation("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")
    testImplementation("net.md-5:bungeecord-chat:1.21-R0.4")
    testImplementation("net.kyori:adventure-text-serializer-gson:4.25.0")
    testImplementation("net.kyori:adventure-text-serializer-legacy:4.25.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:4.25.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

taboolib { subproject = true }

// 测试复用宿主资源，不构成生产反向依赖。
tasks.processTestResources {
    from(project(":project:bukkit").layout.projectDirectory.dir("src/main/resources")) {
        include("config.yml", "core/**", "defaults/bundle.json")
    }
}
