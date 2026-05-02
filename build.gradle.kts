plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "dev.versk"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.langchain4j:langchain4j-ollama:1.0.0-beta3")
    implementation("dev.langchain4j:langchain4j:1.0.0-beta3")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("IndirectInjectionDemoKt")
}
