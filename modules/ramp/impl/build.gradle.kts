plugins {
    id("circaso.impl")
}

// The ramp: an automated P2P merchant on-ramp, USDC for KES over M-Pesa (decision 2026-11-22). The one module that
// moves money: only out of a partner merchant's developer-controlled wallet, only against a payment it has verified,
// and only from the ops process, which alone can sign. Account status and the verified-phone check come from
// identity's contract, the user's deposit address from wallet's.
dependencies {
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:wallet:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    // Argon2id, which stretches a merchant's PIN for the credential envelope (data/support/EnvelopeVault).
    implementation(libs.bouncycastle.provider)
    // WebAuthn (passkeys on the merchant portal, 2026-11-24): Yubico's relying-party library checks registrations
    // and assertions (data/support/YubicoPasskeys).
    implementation(libs.yubico.webauthn.server)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.logback.classic)
    // Infrastructure's test fixtures: the PostgreSQL container for the data-layer tests (infrastructure:api types only).
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
