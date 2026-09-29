package ru.whensclass.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Раздел «Работа в фоне»: на каких телефонах он есть и куда ведут его кнопки. */
class BackgroundTest {

    @Test
    fun `марка по производителю и бренду`() {
        assertEquals(Vendor.XIAOMI, Vendor.of("Xiaomi", "Redmi"))
        assertEquals(Vendor.XIAOMI, Vendor.of("Xiaomi", "POCO"))
        assertEquals(Vendor.HUAWEI, Vendor.of("HUAWEI", "HUAWEI"))
        // Ранние Honor выпускала Huawei — экран у них всё равно свой.
        assertEquals(Vendor.HONOR, Vendor.of("HUAWEI", "HONOR"))
        assertEquals(Vendor.HONOR, Vendor.of("HONOR", "HONOR"))
        assertEquals(Vendor.SAMSUNG, Vendor.of("samsung", "samsung"))
        assertEquals(Vendor.TRANSSION, Vendor.of("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(Vendor.TRANSSION, Vendor.of("INFINIX MOBILITY LIMITED", "Infinix"))
        assertEquals(Vendor.OPPO, Vendor.of("realme", "realme"))
        assertEquals(Vendor.OPPO, Vendor.of("OnePlus", "OnePlus"))
        assertEquals(Vendor.OPPO, Vendor.of("OPPO", "OPPO"))
        assertEquals(Vendor.VIVO, Vendor.of("vivo", "vivo"))
        assertEquals(Vendor.VIVO, Vendor.of("vivo", "iQOO"))
        // Остальным раздел не нужен: обычный Android будит приложение сам.
        assertNull(Vendor.of("Google", "google"))
        assertNull(Vendor.of(null, null))
    }

    @Test
    fun `марка словами — как у людей`() {
        assertEquals("Redmi", Vendor.XIAOMI.title("Redmi"))
        assertEquals("Xiaomi", Vendor.XIAOMI.title("Xiaomi"))
        assertEquals("Infinix", Vendor.TRANSSION.title("Infinix"))
        assertEquals("Tecno", Vendor.TRANSSION.title("TECNO"))
    }

    @Test
    fun `у каждой марки есть куда отступить`() {
        for (vendor in Vendor.entries) {
            val steps = Background.steps(vendor)
            assertTrue(vendor.name, steps.isNotEmpty())
            for (step in steps) {
                // Нужный экран могут переименовать — тогда свойства приложения.
                val last = step.targets.last()
                assertTrue(step.label, last is SettingsTarget.Action && last.withPackage)
            }
        }
    }
}
