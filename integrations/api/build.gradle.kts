plugins {
    id("circaso.api")
}

// Contracts named for what Circaso needs, not for a vendor: SmsGateway, EmailGateway, SocialSignIn,
// CarrierPhoneVerification, PushGateway. Wallet, calls and cash-out contracts arrive with their modules.
