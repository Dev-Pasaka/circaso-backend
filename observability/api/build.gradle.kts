plugins {
    id("circaso.api")
}

// The `Telemetry` handle modules borrow for business metrics and manual spans, in Circaso's own types.
// Arrives in M1; it moved here from infrastructure:api.
