plugins {
    id("circaso.api")
}

// wallet's contract (Wallets, events). Read by the assistant later (Phase B proposals). Wallet implements identity's
// DeletionGuard, so the contract it answers comes with it.
dependencies {
    api(project(":modules:identity:api"))
}
