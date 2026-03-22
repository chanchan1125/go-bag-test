package com.gobag.core.common

import java.util.UUID

object DeviceIdProvider {
    fun generate(): String = UUID.randomUUID().toString()
}
