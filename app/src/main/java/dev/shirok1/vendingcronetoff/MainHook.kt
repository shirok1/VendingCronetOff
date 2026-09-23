package dev.shirok1.vendingcronetoff

import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.min

/**
 * Disable Play Store's Cronet path in com.android.vending.
 *
 * This module avoids crashes from ROM/APEX Cronet (e.g. /apex/.../libcronet.*.so)
 * by blocking HttpEngine/Cronet provider entry points and forcing fallback transport.
 */
class MainHook : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if ("com.android.vending" != param.packageName) {
            return
        }

        log("hooking com.android.vending (pid=" + Process.myPid() + ")")

        hookHttpEngineBuild()

        hookHttpEngineNativeProvider(param.classLoader)

        hookNativeCronetProvider(param.classLoader)

        hookAppCronetBuilders(param.classLoader)

        dumpAndHookProviderHierarchy(param.classLoader)
    }

    private fun hookHttpEngineBuild() {
        var hooked = false

        hooked = hooked or hookBuildMethodOnly(
            null,
            $$"android.net.http.HttpEngine$Builder",
            "bootclasspath",
        )

        hooked = hooked or hookStaticFactories(null, "android.net.http.HttpEngine")

        if (hooked) {
            log($$"Phase1: HttpEngine$Builder#build() hooked (constructors left open for graceful fallback)")
        } else {
            log($$"Phase1: android.net.http.HttpEngine$Builder not found on bootclasspath")
        }
    }

    private fun hookHttpEngineNativeProvider(classLoader: ClassLoader) {
        val className = "org.chromium.net.impl.HttpEngineNativeProvider"
        val clazz = findClass(classLoader, className)
        if (clazz == null) {
            log("Phase2: $className not found in app classloader")
            return
        }

        val methods = clazz.declaredMethods
        var hookedBool = 0
        var hookedOther = 0

        for (m in methods) {
            val name = m.name
            if (isStandardObjectMethod(name)) continue

            val ret = m.returnType

            if (ret == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                // isEnabled() or obfuscated boolean -> false
                try {
                    hook(m as Executable).intercept { false }
                    hookedBool++
                    log("Phase2: $className#$name() -> false")
                } catch (t: Throwable) {
                    log("Phase2: failed hooking $name: ${t.message}")
                }
            } else {
                // All other methods -> return null/default
                try {
                    hook(m as Executable).intercept {
                        log("Phase2: blocked $className#$name() ret=${ret.name}")
                        getDefaultValue(ret)
                    }
                    hookedOther++
                } catch (t: Throwable) {
                    log("Phase2: failed hooking $className#$name: ${t.message}")
                }
            }
        }

        for (ctor in clazz.declaredConstructors) {
            try {
                hook(ctor as Executable).intercept { chain ->
                    val res = chain.proceed()
                    log("Phase2: HttpEngineNativeProvider constructed")
                    logStackBrief()
                    res
                }
            } catch (_: Throwable) {
            }
        }

        log("Phase2: HttpEngineNativeProvider hooked: $hookedBool bool, $hookedOther other")
    }

    private fun hookNativeCronetProvider(classLoader: ClassLoader) {
        val className = "org.chromium.net.impl.NativeCronetProvider"
        val clazz = findClass(classLoader, className)
        if (clazz == null) {
            log("Phase3: $className not found")
            return
        }

        val methods = clazz.declaredMethods
        var hookedBool = 0
        var hookedOther = 0

        for (m in methods) {
            val name = m.name
            if (isStandardObjectMethod(name)) continue

            val ret = m.returnType

            if (ret == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                try {
                    hook(m as Executable).intercept { false }
                    hookedBool++
                    log("Phase3: $className#$name() -> false")
                } catch (t: Throwable) {
                    log("Phase3: failed hooking $name: ${t.message}")
                }
            } else {
                try {
                    hook(m as Executable).intercept {
                        getDefaultValue(ret)
                    }
                    hookedOther++
                } catch (_: Throwable) {
                    log("Phase3: failed hooking $className#$name")
                }
            }
        }

        log("Phase3: NativeCronetProvider hooked: $hookedBool bool, $hookedOther other")
    }

    private fun hookAppCronetBuilders(classLoader: ClassLoader) {
        val candidates = arrayOf(
            $$"org.chromium.net.CronetEngine$Builder",
            $$"org.chromium.net.ExperimentalCronetEngine$Builder",
            "org.chromium.net.impl.NativeCronetEngineBuilderImpl",
            "org.chromium.net.impl.CronetEngineBuilderImpl",
            "org.chromium.net.CronetEngine",
            "org.chromium.net.ExperimentalCronetEngine",
            "org.chromium.net.impl.NativeCronetEngine",
        )

        var count = 0
        for (cls in candidates) {
            val a = hookBuildMethodOnly(classLoader, cls, "app")
            val b = hookStaticFactories(classLoader, cls)
            if (a || b) count++
        }

        log("Phase4: app-classloader Cronet classes hooked: $count")
    }

    private fun dumpAndHookProviderHierarchy(classLoader: ClassLoader) {
        walkHierarchy(classLoader, "org.chromium.net.impl.HttpEngineNativeProvider")
        walkHierarchy(classLoader, "org.chromium.net.impl.NativeCronetProvider")
    }

    private fun walkHierarchy(classLoader: ClassLoader, className: String) {
        val clazz = findClass(classLoader, className) ?: return

        var parent: Class<*>? = clazz.superclass
        while (parent != null && parent != Any::class.java) {
            val parentName = parent.name
            log("Phase5: $className parent: $parentName")

            val parentMethods = parent.declaredMethods
            val sb = StringBuilder()
            sb.append("Phase5: ").append(parentName).append(" methods:")
            for (m in parentMethods) {
                sb.append("\n  ").append(Modifier.toString(m.modifiers)).append(" ")
                    .append(m.returnType.simpleName).append(" ").append(m.name).append("(")
                m.parameterTypes.joinTo(sb) { it.simpleName }
                sb.append(")")
            }
            log(sb.toString())

            for (m in parentMethods) {
                val name = m.name
                if (isStandardObjectMethod(name)) continue

                val ret = m.returnType
                if (shouldHookParentMethod(name, ret)) {
                    try {
                        hook(m as Executable).intercept {
                            log("Phase5: blocked $parentName#$name() ret=${ret.name}")
                            getDefaultValue(ret)
                        }
                        log("Phase5: hooked $parentName#$name()")
                    } catch (_: Throwable) {
                        // already hooked or abstract; ok
                    }
                }
            }

            parent = parent.superclass
        }

        // Log interfaces
        for (iface in clazz.interfaces) {
            log("Phase5: $className implements: ${iface.name}")
        }
    }

    private fun shouldHookParentMethod(name: String, ret: Class<*>): Boolean {
        val retName = ret.name.lowercase(Locale.getDefault())
        val nameLower = name.lowercase(Locale.getDefault())

        if (retName.contains("cronet") || retName.contains("engine") || retName.contains("builder") || retName.contains(
                "chromium"
            )
        ) {
            return true
        }
        if (nameLower.contains("create") || nameLower.contains("build") || nameLower.contains("engine") || nameLower.contains(
                "builder"
            )
        ) {
            return true
        }
        // Short obfuscated names returning objects
        return name.length <= 2 && ret != Void.TYPE && ret != Boolean::class.javaPrimitiveType
    }

    private fun hookBuildMethodOnly(cl: ClassLoader?, className: String, source: String?): Boolean {
        val clazz = findClass(cl, className) ?: return false

        var hooked = false
        for (m in clazz.declaredMethods) {
            val name = m.name
            if ("build" != name && "create" != name) continue
            try {
                hook(m as Executable).intercept {
                    log("BLOCKED: $className#$name() [$source]")
                    logStackBrief()
                    throw RuntimeException("Cronet disabled by VendingCronetOff")
                }
                hooked = true
                log("hooked $className#$name() [$source]")
            } catch (_: Throwable) {
                // ignore
            }
        }
        return hooked
    }

    private fun hookStaticFactories(cl: ClassLoader?, className: String): Boolean {
        val clazz = findClass(cl, className) ?: return false

        var hooked = false
        for (m in clazz.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers)) continue
            val name = m.name
            val lower = name.lowercase(Locale.getDefault())
            if (lower.contains("builder") || lower.contains("create") || lower.contains("new") || lower.contains(
                    "engine"
                ) || lower.contains("instance")
            ) {
                try {
                    hook(m as Executable).intercept {
                        log("BLOCKED static: $className#$name()")
                        throw RuntimeException("Cronet disabled by VendingCronetOff")
                    }
                    hooked = true
                    log("hooked static $className#$name()")
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
        return hooked
    }

    private fun findClass(cl: ClassLoader?, className: String): Class<*>? {
        return try {
            if (cl != null) cl.loadClass(className) else Class.forName(className)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun log(msg: String) {
        log(Log.INFO, TAG, msg)
    }

    private fun logStackBrief() {
        try {
            val st = Throwable().stackTrace
            val sb = StringBuilder()
            sb.append(TAG).append(": callstack:")
            val n = min(15, st.size)
            for (i in 2..<n) {
                sb.append("\n  at ").append(st[i])
            }
            log(sb.toString())
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "VendingCronetOff"

        private fun getDefaultValue(type: Class<*>): Any? {
            if (!type.isPrimitive) return null
            if (type == Boolean::class.javaPrimitiveType) return false
            if (type == Int::class.javaPrimitiveType) return 0
            if (type == Long::class.javaPrimitiveType) return 0L
            if (type == Float::class.javaPrimitiveType) return 0f
            if (type == Double::class.javaPrimitiveType) return 0.0
            if (type == Short::class.javaPrimitiveType) return 0.toShort()
            if (type == Byte::class.javaPrimitiveType) return 0.toByte()
            if (type == Char::class.javaPrimitiveType) return '\u0000'
            return null
        }

        private fun isStandardObjectMethod(name: String?): Boolean {
            return "equals" == name || "hashCode" == name || "toString" == name || "getClass" == name || "notify" == name || "notifyAll" == name || "wait" == name || "finalize" == name || "clone" == name
        }
    }
}
