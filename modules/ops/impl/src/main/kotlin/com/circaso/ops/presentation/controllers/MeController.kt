package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.MeResponse
import com.circaso.ops.presentation.dto.StaffDto
import com.circaso.ops.presentation.mappers.TAG_ME
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.services.MeService
import com.circaso.shared.StaffRole
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staff
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** `/v1/ops/me`: who the caller is and what their role may do, so the portal can build its menu. */
fun Route.meController(me: MeService, environment: String) {
    route("/ops/me") {
        staffOnly(StaffRole.entries.toSet()) {
            get {
                val staff = call.staff()
                val result =
                    me.permissions(staff.role).map { permissions ->
                        MeResponse(
                            staff =
                                StaffDto(
                                    id = staff.staffId.toString(),
                                    email = staff.email,
                                    role = staff.role.name,
                                    active = true,
                                    fromConfig = false,
                                    createdAt = "",
                                    updatedAt = "",
                                ),
                            signInMethod = staff.signIn.name,
                            environment = environment,
                            permissions = permissions.map(Permission::wire),
                        )
                    }
                call.respondDefault(result.toDefaultResponse { it })
            }.docs<NoBody, MeResponse>(
                "OPS · You and what your role may do",
                TAG_ME,
                notes = "${roles("any")} `permissions` lists the role's permissions; the server enforces each route.",
            )
        }
    }
}
