plugins {
    `kotlin-dsl`
}

kotlin{
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(localGroovy())
}
