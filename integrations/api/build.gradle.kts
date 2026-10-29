plugins {
    id("circaso.api")
}

// Contracts named for what Circaso needs, not for a vendor: SmsGateway, EmailGateway, SocialSignIn,
// CarrierPhoneVerification, PushGateway, StablecoinCustody (the wallet's), CallMedia (calls' SFU). Cash-out contracts
// arrive with their module.
