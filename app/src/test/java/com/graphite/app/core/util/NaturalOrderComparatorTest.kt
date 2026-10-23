package com.graphite.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {

    @Test
    fun testNaturalOrderSortingAscending() {
        val titles = listOf(
            "The Adventures of Tintin 5 The Blue Lotus",
            "The Adventures of Tintin 24 Tintin and Alph Art",
            "The Adventures of Tintin 1 Tintin in the Land of the Soviets",
            "The Adventures of Tintin 22 Flight 714",
            "The Adventures of Tintin 3 Tintin in America",
            "The Adventures of Tintin 4 Cigars of the Pharaoh",
            "The Adventures of Tintin 21 The Castafiore Emerald",
            "The Adventures of Tintin 23 Tintin and The Picaros",
            "The Adventures of Tintin 2 Tintin in the Congo"
        )

        val sortedAsc = titles.sortedWith { a, b -> NaturalOrderComparator.compare(a, b) }

        val expectedOrderAsc = listOf(
            "The Adventures of Tintin 1 Tintin in the Land of the Soviets",
            "The Adventures of Tintin 2 Tintin in the Congo",
            "The Adventures of Tintin 3 Tintin in America",
            "The Adventures of Tintin 4 Cigars of the Pharaoh",
            "The Adventures of Tintin 5 The Blue Lotus",
            "The Adventures of Tintin 21 The Castafiore Emerald",
            "The Adventures of Tintin 22 Flight 714",
            "The Adventures of Tintin 23 Tintin and The Picaros",
            "The Adventures of Tintin 24 Tintin and Alph Art"
        )

        assertEquals(expectedOrderAsc, sortedAsc)
    }

    @Test
    fun testNaturalOrderSortingDescending() {
        val titles = listOf(
            "The Adventures of Tintin 5 The Blue Lotus",
            "The Adventures of Tintin 4 Cigars of the Pharaoh",
            "The Adventures of Tintin 3 Tintin in America",
            "The Adventures of Tintin 24 Tintin and Alph Art",
            "The Adventures of Tintin 23 Tintin and The Picaros",
            "The Adventures of Tintin 22 Flight 714",
            "The Adventures of Tintin 21 The Castafiore Emerald"
        )

        val sortedDesc = titles.sortedWith { a, b -> NaturalOrderComparator.compare(b, a) }

        // In Title (Z-A), 24 must come before 23, 22, 21, and 21 must come before 5, 4, 3!
        assertEquals("The Adventures of Tintin 24 Tintin and Alph Art", sortedDesc[0])
        assertEquals("The Adventures of Tintin 23 Tintin and The Picaros", sortedDesc[1])
        assertEquals("The Adventures of Tintin 22 Flight 714", sortedDesc[2])
        assertEquals("The Adventures of Tintin 21 The Castafiore Emerald", sortedDesc[3])
        assertEquals("The Adventures of Tintin 5 The Blue Lotus", sortedDesc[4])
        assertEquals("The Adventures of Tintin 4 Cigars of the Pharaoh", sortedDesc[5])
        assertEquals("The Adventures of Tintin 3 Tintin in America", sortedDesc[6])
    }
}
