plugins { application }

dependencies {
    implementation(project(":project:core"))
    implementation(project(":project:pack"))
    implementation(project(":project:authoring"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("io.izzel.taboolib:common-util:6.3.0-0e3a911")
    implementation("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")
    implementation("net.md-5:bungeecord-chat:1.21-R0.4")
    implementation("net.kyori:adventure-text-serializer-gson:4.25.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.25.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.25.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

taboolib { subproject = true }

application {
    applicationName = "rafter-author"
    mainClass.set("com.hiusers.rafter.cli.MainKt")
    applicationDefaultJvmArgs =
        listOf("-Djava.awt.headless=true", "-Xmx384m", "-XX:ActiveProcessorCount=2")
}

tasks.processResources {
    inputs.property("engineVersion", rootProject.version.toString())
    filesMatching("rafter-author.properties") {
        expand("version" to rootProject.version.toString())
    }
}

tasks.named("build") { dependsOn(tasks.named("installDist")) }
