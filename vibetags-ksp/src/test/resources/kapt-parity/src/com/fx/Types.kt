@file:JvmName("TypesFacade")
package com.fx

import se.deversity.vibetags.annotations.AILocked
import se.deversity.vibetags.annotations.AILoadBearing
import se.deversity.vibetags.annotations.AIInputSanitized

open class Animal
class Dog : Animal()

@AILocked(reason = "top fun") fun topLevel(a: Int, b: String?): Unit {}
@AILocked(reason = "top prop") val topProp: String = "x"
@AILocked(reason = "top const") const val TOP_CONST: Int = 1
@AILocked(reason = "ext fun") fun String.shout(times: Int): String = this
@get:AILocked(reason = "ext prop getter") val String.len2: Int get() = length

@AILocked(reason = "plain class")
class Plain(@AILoadBearing(invariant = "ctor val param") val id: Long,
            @AIInputSanitized(AIInputSanitized.SanitizerType.SQL_INJECTION) name: String,
            @AILocked(reason = "ctor val field-only") val tag: String) {

    @AILocked(reason = "secondary ctor") constructor(id: Long) : this(id, "n", "t")

    @AILocked(reason = "field var") var counter: Int = 0
    @AILocked(reason = "lateinit") lateinit var late: String
    @JvmField @AILocked(reason = "jvmfield") var jf: Double = 0.0
    @get:AILocked(reason = "getter") val computed: Int get() = 1
    @set:AILocked(reason = "setter") var settable: Int = 0
    @setparam:AILoadBearing(invariant = "setparam") var sp: String = ""
    @field:AILocked(reason = "field target") val ft: Boolean = true
    @AILocked(reason = "private prop") private val secret: String = "s"

    @AILocked(reason = "plain fun") fun plain(): Unit {}
    @AILocked(reason = "overload a") fun over(x: Int): Int = x
    @AILocked(reason = "overload b") fun over(x: String): String = x
    @AILocked(reason = "vararg") fun va(first: Int, vararg rest: String) {}
    @AILocked(reason = "generic") fun <T : Comparable<T>> gen(item: T, list: List<T>): T = item
    @AILocked(reason = "suspend") suspend fun sus(q: String): Int = 1
    @AILocked(reason = "jvmname") @JvmName("renamed") fun original(): Unit {}
    @AILocked(reason = "overloads") @JvmOverloads fun defs(a: Int, b: String = "b", c: Long = 1L) {}
    @AILocked(reason = "internal") internal fun inside(s: String) {}
    @AILocked(reason = "private fun") private fun hidden(): Unit {}
    @AILocked(reason = "prims") fun prims(a: Byte, b: Short, c: Char, d: Long, e: Float, f: Double, g: Boolean, h: Int?) {}
    @AILocked(reason = "arrays") fun arrays(a: IntArray, b: Array<String>, c: Array<Int>, d: Array<out Any>, e: Array<IntArray>) {}
    @AILocked(reason = "colls") fun colls(a: List<String>, b: MutableList<String>, c: Map<String, Int>, d: Set<Animal>, e: List<Dog>, f: Collection<*>, g: MutableMap<String, in Animal>) {}
    @AILocked(reason = "fn types") fun fns(f: (Int) -> String, g: suspend () -> Unit, h: String.() -> Int) {}
    @AILocked(reason = "result") fun res(r: Result<String>) {}
    @AILocked(reason = "any nothing") fun anys(a: Any, b: Any?, c: Nothing?): Nothing? = null
    @AILocked(reason = "returns list") fun ret(): List<Animal> = emptyList()
    @AILocked(reason = "param ann") fun paramAnn(@AILoadBearing(invariant = "p1") first: String, @AIInputSanitized(AIInputSanitized.SanitizerType.SQL_INJECTION) second: Int) {}
    @AILocked(reason = "inline reified") inline fun <reified R> reified(x: Any): Boolean = x is R
    @AILocked(reason = "value class param") fun vc(id: UserId) {}
    @AILocked(reason = "value class return") fun vcRet(): UserId = UserId("a")
    @AILocked(reason = "operator") operator fun plus(o: Plain): Plain = this
    @AILocked(reason = "infix") infix fun with(o: Int): Int = o
    @AILocked(reason = "typealias") fun alias(h: Handler) {}
    @AILocked(reason = "unit returning lambda") fun lam(cb: () -> Unit) {}
    @AILocked(reason = "nested generic") fun ng(m: Map<String, List<Pair<Int, Dog?>>>) {}

    @AILocked(reason = "nested class") class Nested { @AILocked(reason = "nested fun") fun nf(n: Nested) {} }
    @AILocked(reason = "inner class") inner class Inner(@AILoadBearing(invariant = "inner ctor p") val q: Int) { @AILocked(reason = "inner fun") fun inf() {} }

    companion object {
        @AILocked(reason = "companion val") val cval: String = "c"
        @AILocked(reason = "companion const") const val CCONST: String = "cc"
        @JvmStatic @AILocked(reason = "jvmstatic") fun create(): Plain = Plain(1)
        @AILocked(reason = "companion fun") fun make(): Plain = Plain(2)
        @JvmField @AILocked(reason = "companion jvmfield") val cjf: Int = 3
    }
}

typealias Handler = (String) -> Unit

@JvmInline value class UserId(val raw: String)

@AILocked(reason = "object")
object Singleton {
    @AILocked(reason = "object fun") fun of(): Int = 1
    @JvmStatic @AILocked(reason = "object jvmstatic") fun st(): Int = 2
    @AILocked(reason = "object val") val ov: Int = 4
}

@AILocked(reason = "enum")
enum class Color(val rgb: Int) {
    @AILocked(reason = "enum const") RED(1), GREEN(2);
    @AILocked(reason = "enum fun") fun hex(): String = ""
}

@AILocked(reason = "interface")
interface Shape {
    @AILocked(reason = "abstract fun") fun area(): Double
    @AILocked(reason = "default fun") fun describe(prefix: String): String = prefix
    @get:AILocked(reason = "iface prop") val sides: Int
    companion object { @AILocked(reason = "iface companion fun") fun unit(): Int = 1 }
}

@AILocked(reason = "sealed") sealed class Result2 { @AILocked(reason = "sealed sub") data class Ok(val v: Int) : Result2() }
@AILocked(reason = "data") data class Point(val x: Int, @AILoadBearing(invariant = "data param") val y: Int)
@AILocked(reason = "generic class") class Box<T : Animal, in K>(val t: T) { @AILocked(reason = "box fun") fun put(k: K, t: T): T = t }
@AILocked(reason = "abstract") abstract class Base { @AILocked(reason = "abstract member") abstract fun go(x: Int) }
@AILocked(reason = "annotation class") annotation class Marker
