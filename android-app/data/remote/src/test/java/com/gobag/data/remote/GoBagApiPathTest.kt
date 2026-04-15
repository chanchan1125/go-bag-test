package com.gobag.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class GoBagApiPathTest {
    @Test
    fun relay_paths_are_relative() {
        val annotatedPaths = GoBagApi::class.java.methods.mapNotNull { method ->
            method.getAnnotation(GET::class.java)?.value
                ?: method.getAnnotation(POST::class.java)?.value
        }

        assertEquals(
            listOf(
                "health",
                "device/status",
                "sync/status",
                "time",
                "templates",
                "device/bag",
                "pair",
                "sync"
            ),
            annotatedPaths
        )
        assertTrue(
            "Retrofit paths must stay relative so relay URLs keep their /r/<pi_device_id> prefix.",
            annotatedPaths.none { it.startsWith("/") }
        )
    }
}
