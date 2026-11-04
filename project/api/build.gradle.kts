import java.io.File

version = rootProject.version

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:mapped")
}

taboolib { subproject = true }

base { archivesName.set("rafter-api") }

java { withSourcesJar() }

// 仅核对已有产物，不隐式执行 DeleteCode 或发布。
tasks.register("verifyApiAbi") {
    group = "verification"
    description = "Verify Java/Kotlin consumers against the API and deployment JARs"
    val apiJar = layout.buildDirectory.file("libs/rafter-api-${project.version}-api.jar")
    val implementationJar =
        providers
            .gradleProperty("rafterVerificationPluginJar")
            .map { rootProject.file(it) }
            .orElse(rootProject.layout.projectDirectory.file("plugin/build/libs/Rafter.jar").asFile)
    inputs.file(apiJar)
    inputs.file(implementationJar)
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/api-consumer/src"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/verify-api.py"))
    outputs.upToDateWhen { false }
    doLast {
        val dependencies = configurations.getByName("compileClasspath").files
        val compiler = configurations.getByName("kotlinCompilerClasspath").files
        val check = providers.exec {
            isIgnoreExitValue = true
            commandLine(
                "python3",
                rootProject.file("scripts/verify-api.py").absolutePath,
                "--java-home",
                System.getProperty("java.home"),
                "--api-jar",
                apiJar.get().asFile.absolutePath,
                "--plugin-jar",
                implementationJar.get().absolutePath,
                "--classpath",
                dependencies.joinToString(File.pathSeparator),
                "--kotlin-compiler-classpath",
                compiler.joinToString(File.pathSeparator),
            )
        }
        check.standardOutput.asText.get().takeIf(String::isNotBlank)?.let(logger::lifecycle)
        check.standardError.asText.get().takeIf(String::isNotBlank)?.let(logger::error)
        check.result.get().assertNormalExitValue()
    }
}
