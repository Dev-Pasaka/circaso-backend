package com.circaso.users.api

// A response another module consumes may live in a module's api project; a request may not.
data class AgentContactsResponse(val contacts: List<String>)

data class AgentSearchRequest(val query: String)
