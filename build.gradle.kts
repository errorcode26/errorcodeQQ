import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "errorcode26/errorcodeQQ")
    }

    android {
        namespace = "com.example"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        lint {
            targetSdk = 36
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        //noinspection WrongGradleMethod
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.18") // HTTP Lib
        implementation("org.jsoup:jsoup:1.22.2") // HTML Parser
        implementation("androidx.annotation:annotation:1.10.0")
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will break compatibility
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.mozilla:rhino:1.8.1")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.14.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
        implementation("org.jspecify:jspecify:1.0.0")
        // Testing dependencies
        add("testImplementation", "junit:junit:4.13.2")
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.1")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.10.1")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.1")
        add("testRuntimeOnly", "org.junit.vintage:junit-vintage-engine:5.10.1")
        add("testImplementation", files("${System.getProperty("user.home")}/.gradle/caches/cloudstream/cloudstream/cloudstream.jar"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}