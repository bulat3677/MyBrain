plugins {
    kotlin("jvm") version "2.3.0"
}
repositories { mavenCentral() }
dependencies { testImplementation(kotlin("test")) }
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
