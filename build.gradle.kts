plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)

    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "hu.bme.aut.android.demo"
version = "0.0.1"

application {
    mainClass.set("hu.bme.aut.android.demo.ApplicationKt")
}

// Konfiguráció a shadowJar pluginhoz, biztosítva, hogy a fő osztály fusson
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("all") // A létrehozott JAR neve: projekt-all.jar
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

dependencies {
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)

    // WebSocket támogatás
    implementation(libs.ktor.server.websockets)

    // Firebase Admin SDK a Push értesítésekhez
    implementation(libs.firebase.admin)
    implementation(libs.ktor.server.auth)

    // Adatbázis (HikariCP + Exposed + PostgreSQL)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)

    // Dátum/Idő típusok támogatása az Exposed-ban
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)

    // Teszt függőségek
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
