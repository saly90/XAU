import java.io.File
import java.io.ByteArrayInputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
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
        val payload = encoded.asFile.readText().filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }.trimEnd('=')
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        val source = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw GradleException("Launcher photo is not a valid image")

        fun render(src: BufferedImage, width: Int, height: Int): BufferedImage {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, width, height, null)
            g.dispose()
            return out
        }

        val densitySizes = mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )
        densitySizes.forEach { (folder, size) ->
            val dir = generatedRes.get().dir(folder).asFile
            dir.mkdirs()
            ImageIO.write(render(source, size, size), "png", File(dir, "ic_launcher_photo.png"))
        }

        val drawableDir = generatedRes.get().dir("drawable-nodpi").asFile
        drawableDir.mkdirs()
        val adaptive = BufferedImage(432, 432, BufferedImage.TYPE_INT_ARGB)
        val g = adaptive.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val inset = 54
        g.drawImage(source, inset, inset, 432 - inset, 432 - inset, null)
        g.dispose()
        ImageIO.write(adaptive, "png", File(drawableDir, "ic_launcher_photo_foreground.png"))

        val anydpi = generatedRes.get().dir("mipmap-anydpi-v26").asFile
        anydpi.mkdirs()
        File(anydpi, "ic_launcher_photo.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black" />
    <foreground android:drawable="@drawable/ic_launcher_photo_foreground" />
</adaptive-icon>
"""
        )
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
