plugins {
    id("circaso.api")
}

// status' contract: the id of a moment and the realtime frames it sends (module `status`). Nothing else asks it
// anything yet, so it depends on `shared` alone.
