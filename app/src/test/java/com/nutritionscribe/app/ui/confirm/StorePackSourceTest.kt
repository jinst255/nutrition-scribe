package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Origin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check card names a store pack when it answered (owner, 11-19, approving the
 * "Found in your Great Value pack" scan). Store-pack ids are slot × 1e9 + n, so
 * slot 1 (Great Value) is label:1000000001 onward; the main label pack's ids are
 * small and keep saying "Package label".
 */
class StorePackSourceTest {
    private fun c(id: String, origin: Origin = Origin.LABEL) = FoodCandidate(id = id, name = "Small curd cottage cheese", origin = origin)

    @Test
    fun `a store pack product names its pack`() {
        assertEquals("Package label · Great Value pack", ItemDraft.label(c("label:1000000042")))
        assertEquals("Package label · Kirkland Signature pack", ItemDraft.label(c("label:2000000007")))
    }

    @Test
    fun `the main label pack and other sources are unchanged`() {
        assertEquals("Package label", ItemDraft.label(c("label:1234567")))
        assertEquals("Found online", ItemDraft.label(c("web:abc", Origin.AI)))
        assertEquals("USDA", ItemDraft.label(c("usda:2341362", Origin.USDA)))
        assertEquals("Not found", ItemDraft.label(null as FoodCandidate?))
    }
}
