import app.morphe.patches.youtube.music.audio.OpusPreference

fun <T> candidate(group: String, codec: String, quality: Int, value: T) =
    OpusPreference.Candidate(group, codec, quality, value)

fun <T> assertEquals(expected: T, actual: T, label: String) {
    check(expected == actual) { "$label: expected=$expected actual=$actual" }
}

// Opus wins even when AAC advertises a higher quality ordinal.
run {
    val selected = OpusPreference.selectPerGroup(
        listOf(
            candidate("en", "mp4a.40.2", 9, "AAC-High"),
            candidate("en", "opus", 4, "Opus-Low"),
        )
    )
    assertEquals(listOf("Opus-Low"), selected.map { it.value }, "Opus preference")
}

// Quality ranking remains active among Opus candidates.
run {
    val selected = OpusPreference.selectPerGroup(
        listOf(
            candidate("en", "opus", 3, "Opus-Low"),
            candidate("en", "opus", 8, "Opus-High"),
        )
    )
    assertEquals(listOf("Opus-High"), selected.map { it.value }, "Opus quality")
}

// Without Opus, the original highest-quality fallback wins.
run {
    val selected = OpusPreference.selectPerGroup(
        listOf(
            candidate("en", "mp4a.40.2", 4, "AAC-Low"),
            candidate("en", "mp4a.40.2", 7, "AAC-High"),
        )
    )
    assertEquals(listOf("AAC-High"), selected.map { it.value }, "AAC fallback")
}

// Groups remain independent, preserving language/commentary alternatives.
run {
    val selected = OpusPreference.selectPerGroup(
        listOf(
            candidate("en", "mp4a.40.2", 9, "English-AAC"),
            candidate("en", "opus", 6, "English-Opus"),
            candidate("hi", "mp4a.40.2", 8, "Hindi-AAC"),
            candidate("commentary", "opus", 5, "Commentary-Opus"),
        )
    )
    assertEquals(
        listOf("English-Opus", "Hindi-AAC", "Commentary-Opus"),
        selected.map { it.value },
        "Independent groups"
    )
}

println("Force Opus policy tests passed.")
