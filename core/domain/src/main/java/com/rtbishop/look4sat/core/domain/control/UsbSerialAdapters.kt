package com.rtbishop.look4sat.core.domain.control

/** Only known PL2303 products; other Prolific USB products are not serial adapters. */
fun isProlificSerialAdapter(vendorId: Int, productId: Int): Boolean =
    vendorId == 0x067B && productId in setOf(0x2303, 0x23A3, 0x23B3, 0x23C3, 0x23D3, 0x23E3, 0x23F3)
