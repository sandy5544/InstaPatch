dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.morphe.patches.library)
}

android {
    namespace = "app.morphe.extension.youtube"
    defaultConfig { minSdk = 26 }
}
