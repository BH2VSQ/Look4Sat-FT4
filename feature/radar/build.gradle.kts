plugins {
    alias(libs.plugins.convention.featurePlugin)
}

android {
    namespace = "com.rtbishop.look4sat.feature.radar"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":feature:mutual"))
    implementation(project(":feature:cw"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.debug.manifest)
    testImplementation(libs.test.junit4)
    testImplementation(libs.test.coroutines)
}
