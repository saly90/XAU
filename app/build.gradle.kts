import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatePhotoLauncherIcon by tasks.registering {
    val encoded = layout.projectDirectory.file("src/main/icon_source.b64")
    val generatedRes = layout.buildDirectory.dir("generated/photoLauncher/res")
    outputs.dir(generatedRes)
    doLast {
        val payload = encoded.asFile.readText().replace(Regex("[^A-Za-z0-9+/=]"), "").trimEnd('=')
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        if (ImageIO.read(bytes.inputStream()) == null) {
            throw GradleException("Launcher photo is not a valid image")
        }
        val outDir = generatedRes.get().dir("mipmap-xxxhdpi").asFile
        outDir.mkdirs()
        File(outDir, "ic_launcher_photo.jpg").writeBytes(bytes)
    }
}

android {
    namespace = "com.xauai.gold"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xauai.gold"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/photoLauncher/res"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(generatePhotoLauncherIcon)
}
