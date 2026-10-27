package com.circaso.identity.domain.errors

/**
 * A write lost a uniqueness claim to another account: the number ([Claim.PHONE]) or the Google/Apple login
 * ([Claim.PROVIDER_LOGIN]) is already held. The database refused the row, which ends the open transaction, so this is
 * thrown rather than returned: it leaves `Transactor.run` (rolling everything back) and the use case answers with its
 * error code outside.
 */
class ClaimLost(val claim: Claim) : RuntimeException("The ${claim.name.lowercase()} is held by another account") {
    enum class Claim { PHONE, PROVIDER_LOGIN }
}
