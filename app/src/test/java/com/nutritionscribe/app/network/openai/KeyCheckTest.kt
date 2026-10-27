package com.nutritionscribe.app.network.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup key check, read from OpenAI's answer to the free models list. */
class KeyCheckTest {
    @Test fun okIsWorks() = assertEquals(KeyCheck.Result.Works, KeyCheck.classify(200, "{}"))

    /** Testers get restricted keys; one without the models permission still looks food up. */
    @Test fun restrictedKeyIsWorks() = assertEquals(KeyCheck.Result.Works, KeyCheck.classify(403, "insufficient permissions"))

    @Test fun unknownKeyIsRefused() = assertEquals(KeyCheck.Result.Refused, KeyCheck.classify(401, "invalid_api_key"))

    @Test fun noCredit() = assertEquals(KeyCheck.Result.NoCredit, KeyCheck.classify(429, """{"error":{"code":"insufficient_quota"}}"""))

    @Test fun rateLimitedIsStillAWorkingKey() = assertEquals(KeyCheck.Result.Works, KeyCheck.classify(429, "rate_limit_exceeded"))

    @Test fun serverErrorIsUnreachable() = assertTrue(KeyCheck.classify(503, "") is KeyCheck.Result.Unreachable)
}
