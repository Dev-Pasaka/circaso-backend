package com.circaso.wallet.domain.models

/**
 * A coin's mark from the Web3 Icons mono set (decision 2026-11-06): a cleaned, one-colour SVG the app repaints in
 * its theme. [etag] is a hash of [svg], so a phone that already has it is answered 304.
 */
class CoinIcon(val symbol: String, val svg: ByteArray, val etag: String)
