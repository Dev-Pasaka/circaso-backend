package com.circaso.ops.presentation.mappers

// The ops routes' OpenAPI tags and the notes most of them share. Served by the ops gateway's own spec only, never in
// the app's reference.

const val TAG_ME = "Ops · You"
const val TAG_DASHBOARD = "Ops · Dashboard"
const val TAG_USERS = "Ops · Users"
const val TAG_MONEY = "Ops · Money"
const val TAG_GROUPS = "Ops · Groups"
const val TAG_TICKETS = "Ops · Tickets"
const val TAG_CALLS = "Ops · Calls"
const val TAG_DIAGNOSTICS = "Ops · Diagnostic logs"
const val TAG_STAFF = "Ops · Staff and audit"
const val TAG_FEES = "Ops · Fees and limits"

const val MALFORMED = "request.malformed"
const val FORBIDDEN = "ops.role_forbidden"
const val PAGED = "`?cursor=&limit=` (default 50, at most 200); `nextCursor` null on the last page."

fun roles(vararg names: String) = "Roles: ${names.joinToString(", ")}."
