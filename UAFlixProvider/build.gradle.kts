version = 29

dependencies {
    implementation(libs.gson)

    implementation(
        "com.github.teamnewpipe:NewPipeExtractor:v0.25.2"
    )

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"

    description =
        "UAFlix Kids: мультфільми, мультсеріали та вибрані YouTube-канали"

    authors = listOf(
        "proproektor195-create"
    )

    status = 1

    tvTypes = listOf(
        "Cartoon",
        "Movie",
        "TvSeries"
    )

    iconUrl =
        "https://www.google.com/s2/favicons?domain=uafix.net&sz=%size%"
}
