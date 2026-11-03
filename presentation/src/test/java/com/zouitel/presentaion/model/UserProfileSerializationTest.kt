package com.zouitel.presentaion.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UserProfileSerializationTest {
    private val json = Json

    @Test
    fun userProfileUsesCanonicalIdentityFieldNames() {
        val profile = json.decodeFromString<UserProfile>(
            """{"uid":"user-123","fullName":"Jamie Santos","phoneNumber":"+639171234567"}"""
        )

        assertEquals("user-123", profile.uid)
        assertEquals("Jamie Santos", profile.fullName)
        assertEquals("+639171234567", profile.phoneNumber)
    }

    @Test
    fun userProfileRoundTripsWithoutLegacyAliases() {
        val profile = UserProfile(
            uid = "user-456",
            fullName = "Alex Reyes",
            phoneNumber = "+639181234567"
        )

        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<UserProfile>(encoded)

        assertEquals(profile, decoded)
        assertEquals(false, "\"phone\" in encoded)
    }
}
