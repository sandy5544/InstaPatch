dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.morphe.patches.library)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
}

android {
    namespace = "app.morphe.extension.youtube"
    defaultConfig { minSdk = 26 }
}
