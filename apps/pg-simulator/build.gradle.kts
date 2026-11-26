import org.springframework.boot.gradle.tasks.bundling.BootJar

version = "0.7.0"

dependencies {
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

springBoot {
    buildInfo()
}

tasks.withType<BootJar> {
    archiveFileName.set("pg-simulator.jar")
}
