plugins { id("com.android.application") }
android {
    namespace = "dev.alarmoff.fixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.alarmoff.fixture"
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "dev.alarmoff.fixture.test.FlowDriver"
    }
}
