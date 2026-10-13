package com.offlineworkout.data.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// T5.1: source-manifest contract — service (non-exported, specialUse +
// workout_rest_timer subtype), boot receiver (non-exported, BOOT_COMPLETED)
// and the five timer permissions. Plain JVM: deterministic, no device.
class TimerManifestTest {

    private fun manifest(): Element {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("expected module-relative manifest at ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return doc.documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) out.add(nodes.item(i) as Element)
        return out
    }

    private fun Element.attr(name: String): String = getAttribute("android:$name")

    @Test
    fun timer_permissions_declared() {
        val permissions = manifest().children("uses-permission").map { it.attr("name") }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(permissions.contains("android.permission.VIBRATE"))
        assertTrue(permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test
    fun no_exact_alarm_permission() {
        val permissions = manifest().children("uses-permission").map { it.attr("name") }
        assertTrue(permissions.none { it.contains("EXACT_ALARM", ignoreCase = true) })
    }

    @Test
    fun service_is_private_special_use() {
        val application = manifest().children("application").single()
        val service = application.children("service").firstOrNull { it.attr("name") == ".data.timer.RestTimerService" }
        assertTrue(service != null)
        assertEquals("false", service!!.attr("exported"))
        assertEquals("specialUse", service.attr("foregroundServiceType"))
        val property = service.children("property").firstOrNull {
            it.attr("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        }
        assertEquals("workout_rest_timer", property?.attr("value"))
    }

    @Test
    fun boot_receiver_is_private_and_filtered() {
        val application = manifest().children("application").single()
        val receiver = application.children("receiver").firstOrNull {
            it.attr("name") == ".data.timer.BootCompletedReceiver"
        }
        assertTrue(receiver != null)
        assertEquals("false", receiver!!.attr("exported"))
        val actions = receiver.children("intent-filter")
            .flatMap { it.children("action") }
            .map { it.attr("name") }
        assertTrue(actions.contains("android.intent.action.BOOT_COMPLETED"))
    }
}
