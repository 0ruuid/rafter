dependencies {
    implementation(project(":project:api"))
    implementation(project(":project:core"))
    implementation(project(":project:pack"))
    implementation(project(":project:authoring"))
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("com.google.code.gson:gson:2.10.1")
    // 匹配 TabooLib 私有 YAML 命名空间。
    compileOnly("org.yaml:snakeyaml:2.6")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito:mockito-core:5.19.0")
    testImplementation("io.netty:netty-buffer:4.1.114.Final")
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation("io.izzel.taboolib:common-util:6.3.0-0e3a911")
    testImplementation("io.izzel.taboolib:common-platform-api:6.3.0-0e3a911")
    testImplementation("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")
    testImplementation("net.md-5:bungeecord-chat:1.21-R0.4")
    testImplementation("net.kyori:adventure-text-serializer-gson:4.25.0")
    testImplementation("net.kyori:adventure-text-serializer-legacy:4.25.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:4.25.0")
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("ink.ptms.core:v12004:12004:mapped")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

taboolib { subproject = true }
