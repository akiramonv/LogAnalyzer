plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.loganalyzer.cli.LogAnalyzerCli")
    applicationName = "log-analyzer"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

/**
 * Толстый jar: `./gradlew :cli:fatJar` -> cli/build/libs/log-analyzer.jar
 * Позволяет запускать анализатор одной командой `java -jar log-analyzer.jar ...`
 * без Gradle и без classpath-обвязки.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Собирает исполняемый jar со всеми зависимостями"
    archiveFileName.set("log-analyzer.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "io.github.loganalyzer.cli.LogAnalyzerCli",
            "Implementation-Title" to "log-analyzer",
            "Implementation-Version" to project.version
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}

tasks.named("build") { dependsOn(fatJar) }
