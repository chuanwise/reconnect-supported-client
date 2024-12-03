/*
 * Copyright 2024 Chuanwise.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    kotlin("jvm") version "1.8.10"
    id("me.him188.kotlin-jvm-blocking-bridge") version "3.0.0-180.1"
}

group = "cn.chuanwise"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val blockingBridge = "3.0.0-180.1"
    implementation("me.him188:kotlin-jvm-blocking-bridge-runtime:$blockingBridge")

    val coroutines = "1.8.1"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")

    val logging = "5.0.0"
    implementation("io.github.oshai:kotlin-logging-jvm:$logging")

    val slf4j = "2.0.16"
    implementation("org.slf4j:slf4j-api:$slf4j")

    val log4j = "2.24.2"
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:$log4j")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:$log4j")

    val ktor = "2.3.10"
    testImplementation("io.ktor:ktor-server-core-jvm:$ktor")
    testImplementation("io.ktor:ktor-server-websockets-jvm:$ktor")
    testImplementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-websockets:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}