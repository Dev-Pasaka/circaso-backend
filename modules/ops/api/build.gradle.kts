plugins {
    id("circaso.api")
}

// ops' contract (decision 2026-11-08): `StaffDirectory`, which the ops gateway asks who a signed-in email is. Depends on
// `shared` alone.
