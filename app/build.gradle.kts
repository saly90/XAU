plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatePhotoLauncherIcon by tasks.registering {
    val encoded = layout.projectDirectory.file("src/main/icon_source.b64")
    val generatedRes = layout.buildDirectory.dir("generated/photoLauncher/res")
    outputs.dir(generatedRes)
    doLast {
        val bytes = java.util.Base64.getMimeDecoder().decode(encoded.asFile.readText())
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
            ?: throw GradleException("Launcher photo is not a valid image")
        val outDir = generatedRes.get().dir("mipmap-xxxhdpi").asFile
        outDir.mkdirs()
        val out = java.io.File(outDir, "ic_launcher_photo.png")
        val bitmap = java.awt.image.BufferedImage(192, 192, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = bitmap.createGraphics()
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(image, 0, 0, 192, 192, null)
        graphics.dispose()
        javax.imageio.ImageIO.write(bitmap, "png", out)
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
