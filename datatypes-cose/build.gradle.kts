import DatatypeVersions.encoding
import DatatypeVersions.kmmresult
import at.asitplus.gradle.napier
import at.asitplus.gradle.serialization

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("at.asitplus.gradle.conventions")
}

version = "1.0-SNAPSHOT"

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":datatypes"))
                api(serialization("cbor"))
                api("at.asitplus:kmmresult:${kmmresult}")
                // implementation("com.squareup.okio:okio:${okio}")
                implementation(napier())
                implementation("io.matthewnelson.kotlin-components:encoding-base16:${encoding}")
                implementation("io.matthewnelson.kotlin-components:encoding-base64:${encoding}")
            }
        }

        val jvmMain by getting

        val commonTest by getting
        val jvmTest by getting
    }
}