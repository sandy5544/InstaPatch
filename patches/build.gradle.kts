group = "app.instapatch"

patches {
    about {
        name = "InstaPatch"
        description = "Instagram DM pull-to-refresh patch"
        source = "git@github.com:sandy5544/InstaPatch.git"
        author = "sandy5544"
        contact = ""
        website = "https://github.com/sandy5544/InstaPatch"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    implementation(libs.morphe.patches.library)
    implementation(libs.instagram.morphe.patches.library)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
