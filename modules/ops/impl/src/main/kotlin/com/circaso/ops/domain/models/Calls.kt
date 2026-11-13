package com.circaso.ops.domain.models

import com.circaso.calls.api.StaffCall
import com.circaso.shared.UserId

/** One call for staff (2026-11-13): calls' own view of it, and who each participant is from ops' user views. */
data class CallSheet(val call: StaffCall, val people: Map<UserId, UserSummary>)
