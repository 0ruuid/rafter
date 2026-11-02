dependencies {
    implementation(project(":project:api"))
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("io.izzel.taboolib:common-util:6.3.0-0e3a911")
    compileOnly("io.izzel.taboolib:minecraft-chat:6.3.0-0e3a911")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.izzel.taboolib:common-util:6.3.0-0e3a911")
    testImplementation("org.yaml:snakeyaml:2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

taboolib { subproject = true }
