plugins {
    id("circaso.api")
}

// spaces' contract: its ids and the realtime frames it sends (module `spaces`), read typed by the gateway when it
// words a push. Nothing else asks spaces anything through it: the wallet and messaging declare the guards spaces
// implements in their own `api`s, so this depends on `shared` alone.
