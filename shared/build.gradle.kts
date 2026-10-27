plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nutritionscribe.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            // The resolver tests read the real bundled databases. Their paths are
            // passed in rather than guessed, so a test never silently runs against
            // nothing.
            it.systemProperty("foodDb", rootProject.file("app/src/main/assets/food.db").path)
            it.systemProperty("labelPack", System.getProperty("labelPack") ?: "")
            it.systemProperty("exportJson", rootProject.file("protein-scribe-export.json").path)
            it.systemProperty("weekTruth", rootProject.file("tools/.cache/week_truth.json").path)
            it.systemProperty("reportDir", layout.buildDirectory.dir("reports/resolver").get().asFile.path)
            it.systemProperty("aiCacheDir", rootProject.file("tools/.cache").path)
            it.systemProperty("phrases", System.getProperty("phrases") ?: "")
            it.systemProperty("personalFoods", System.getProperty("personalFoods") ?: "")
            it.systemProperty("scanEvents", System.getProperty("scanEvents") ?: "")
            it.systemProperty("askLive", System.getProperty("askLive") ?: "")
            // The accuracy benchmark (BenchRunTest): paths outside the repo, never logged.
            for (p in listOf("bench", "benchOut", "benchModel", "benchOnly")) it.systemProperty(p, System.getProperty(p) ?: "")
            // Only for the opt-in AI replay; absent, those tests skip. Never logged.
            System.getenv("OPENAI_API_KEY")?.let { key -> it.environment("OPENAI_API_KEY", key) }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation("junit:junit:4.13.2")
    // The resolver tests run the real ranking code over the real food.db.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
