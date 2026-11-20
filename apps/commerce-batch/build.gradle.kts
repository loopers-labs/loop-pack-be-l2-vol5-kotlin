plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

// Only the isolated ranking E2E source set consumes the batch's plain classes.
tasks.jar { enabled = true }

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:ranking"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
