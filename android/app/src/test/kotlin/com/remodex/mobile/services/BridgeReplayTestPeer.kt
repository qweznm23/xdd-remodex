package com.remodex.mobile.services

import android.content.SharedPreferences
import com.remodex.mobile.core.persistence.SessionPersistence
import com.remodex.mobile.core.security.SecureStore
import java.lang.reflect.Proxy

/** Only the preferences backend is substituted; Kotlin executes the production pairing and crypto code. */
internal fun replayTestStores(): Pair<SecureStore, SessionPersistence> {
    val values = mutableMapOf<String, Any?>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean" -> { values[args[0] as String] = args[1]; proxy }
            "remove" -> { values.remove(args[0]); proxy }
            "apply" -> null
            else -> error("Unexpected preferences edit: ${method.name}")
        }
    }
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getBoolean" -> values[args[0]] ?: args[1]
            "edit" -> editor
            else -> error("Unexpected preferences read: ${method.name}")
        }
    }
    val type = Class.forName("sun.misc.Unsafe")
    val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    fun <T> allocate(target: Class<T>): T = target.cast(type.getMethod("allocateInstance", Class::class.java).invoke(unsafe, target))
    val store = allocate(SecureStore::class.java)
    val persistence = allocate(SessionPersistence::class.java)
    store.setReplayTestField("prefs", prefs)
    persistence.setReplayTestField("prefs", prefs)
    persistence.setReplayTestField("secureStore", store)
    return store to persistence
}

internal fun Any.setReplayTestField(name: String, value: Any) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}
