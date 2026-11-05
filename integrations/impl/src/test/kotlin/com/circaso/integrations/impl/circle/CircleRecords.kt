package com.circaso.integrations.impl.circle

/**
 * Circle's own records of a real split payment on BASE-SEPOLIA (2026-11-05T06:45Z, `GET /v1/w3s/transactions?txHash=`),
 * user ids and block data replaced: the sender's contract execution (no token, no amount, no `refId`), the payee's
 * inbound leg and Circaso's fee wallet's (developer-controlled, no user).
 */
internal object CircleRecords {
    const val TX_HASH = "0x624040c3b1d5576a05379e7bcd4429a94965f076d853e29438771fe7f51a020e"
    private const val SENDER = "0x893a99d22726c28d82469c226efd1a685ca0c570"

    val SENDER_EXECUTION =
        """{"id":"8822478d-1b93-54c5-87b6-619290cd7d80","blockchain":"BASE-SEPOLIA",""" +
            """"walletId":"5e1a6fd7-e13b-51c7-a1b0-cf13fb183238","sourceAddress":"$SENDER",""" +
            """"contractAddress":"$SENDER","transactionType":"OUTBOUND","custodyType":"ENDUSER","state":"COMPLETE",""" +
            """"transactionScreeningEvaluation":{"screeningDate":"2026-11-05T06:45:48Z"},"amounts":[],"nfts":null,""" +
            """"txHash":"$TX_HASH","blockHash":"0xblock","blockHeight":1,"networkFee":"0.0000018510681",""" +
            """"firstConfirmDate":"2026-11-05T06:45:51Z","operation":"CONTRACT_EXECUTION","userId":"circle-user-1",""" +
            """"abiParameters":null,"createDate":"2026-11-05T06:45:48Z","updateDate":"2026-11-05T06:50:53Z"}"""

    val PAYEE_INBOUND =
        """{"id":"54bc4e3c-fa44-57a4-9f41-6426a126a9c8","blockchain":"BASE-SEPOLIA",""" +
            """"tokenId":"${FakeCircle.USDC_TOKEN_ID}","walletId":"6e62c682-7770-5768-aeed-0b2a59644b24",""" +
            """"sourceAddress":"$SENDER","destinationAddress":"0x419517f1c09a96b2d203a864972341eb513764bd",""" +
            """"transactionType":"INBOUND","custodyType":"ENDUSER","state":"COMPLETE","amounts":["7.699911"],""" +
            """"nfts":null,"txHash":"$TX_HASH","networkFee":"0.000001077609791691","operation":"TRANSFER",""" +
            """"userId":"circle-user-2","abiParameters":null,"createDate":"2026-11-05T06:45:51Z",""" +
            """"updateDate":"2026-11-05T06:50:53Z"}"""

    val FEE_INBOUND =
        """{"id":"7604edc9-dbf3-572c-b5bf-c62fb961dde5","blockchain":"BASE-SEPOLIA",""" +
            """"tokenId":"${FakeCircle.USDC_TOKEN_ID}","walletId":"d4eac6e0-25e4-5fb7-8dc6-deb56bcf9864",""" +
            """"sourceAddress":"$SENDER","destinationAddress":"0xe0d96533f307b5a17d8c04c0d3daa592f39a8148",""" +
            """"transactionType":"INBOUND","custodyType":"DEVELOPER","state":"COMPLETE","amounts":["0.1"],""" +
            """"nfts":null,"txHash":"$TX_HASH","networkFee":"0.000001077609791691","operation":"TRANSFER",""" +
            """"abiParameters":null,"createDate":"2026-11-05T06:45:51Z","updateDate":"2026-11-05T06:50:53Z"}"""

    /**
     * The execution as a webhook may carry it: Circle's webhook sample leaves out `operation`, and an execution never
     * has `tokenId`, which together read as "no id, type or token" and were refused (HTTP 400) twenty times.
     */
    val SENDER_EXECUTION_WEBHOOK =
        """{"id":"8822478d-1b93-54c5-87b6-619290cd7d80","blockchain":"BASE-SEPOLIA",""" +
            """"walletId":"5e1a6fd7-e13b-51c7-a1b0-cf13fb183238","sourceAddress":"$SENDER",""" +
            """"contractAddress":"$SENDER","transactionType":"OUTBOUND","custodyType":"ENDUSER",""" +
            """"state":"CONFIRMED","amounts":[],"nftTokenIds":[],"txHash":"$TX_HASH",""" +
            """"errorReason":"","errorDetails":null,""" +
            """"userId":"circle-user-1","createDate":"2026-11-05T06:45:48Z","updateDate":"2026-11-05T06:45:51Z"}"""
}
