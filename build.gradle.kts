plugins {
    kotlin("jvm") version "1.9.21" apply false
}

group = "com.xingzhi"
version = providers.gradleProperty("version").orElse("2.0.0").get()

allprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
