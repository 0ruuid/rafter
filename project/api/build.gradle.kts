version = rootProject.version

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:mapped")
}

taboolib { subproject = true }

base { archivesName.set("rafter-api") }

java { withSourcesJar() }
