import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.kotlinxSerialization)
}

android {
    namespace = "io.github.qbhx224.lintfile.io"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    api(libs.kotlinx.serialization.json)
    api(libs.androidx.documentfile)

    api(libs.shizuku.api)
    api(libs.shizuku.provider)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "io.github.qbhx224",
        artifactId = "lint-file",
        version = libs.versions.lint.file.get()
    )

    pom {
        name.set("lint-file-android")
        description.set("A file operation library suitable for Android platform.")
        url.set("https://github.com/qbhx224/LintFile")

        licenses {
            license {
                name.set("GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1")
                url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt")
            }
        }

        developers {
            developer {
                name.set("lumkit")
                email.set("2205903933@qq.com")
                url.set("https://github.com/lumkit")
            }
            developer {
                name.set("qbhx224")
                url.set("https://github.com/qbhx224")
            }
        }

        scm {
            url.set("https://github.com/qbhx224/LintFile")
            connection.set("scm:git:git://github.com/qbhx224/LintFile.git")
            developerConnection.set("scm:git:ssh://github.com/qbhx224/LintFile.git")
        }
    }
}