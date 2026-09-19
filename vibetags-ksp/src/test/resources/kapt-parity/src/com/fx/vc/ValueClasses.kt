package com.fx.vc

import se.deversity.vibetags.annotations.AILoadBearing
import se.deversity.vibetags.annotations.AILocked

@JvmInline value class Uid(val raw: String)

@AILocked(reason = "value class itself")
@JvmInline value class Money(@AILocked(reason = "value class field") val cents: Long) {
    @AILocked(reason = "value class member fun") fun doubled(): Money = Money(cents * 2)
    @get:AILocked(reason = "value class member getter") val half: Long get() = cents / 2
}

class Account @AILocked(reason = "ctor with value class") constructor(val id: Uid) {
    @AILocked(reason = "secondary ctor without value class") constructor(raw: Int) : this(Uid(raw.toString()))

    @AILocked(reason = "field of value class type") val owner: Uid = Uid("o")
    @get:AILocked(reason = "getter of value class type") val alias: Uid get() = id
    @set:AILocked(reason = "setter of value class type") var current: Uid = Uid("c")

    @AILocked(reason = "member returns value class") fun idOf(): Uid = id
    @AILocked(reason = "member takes list of value class") fun many(ids: List<Uid>) {}
    @AILocked(reason = "member takes value class, renamed") @JvmName("renamedTake") fun take(id: Uid) {}
    @AILocked(reason = "member takes value class and Result") fun both(id: Uid, r: Result<String>) {}
    @AILocked(reason = "member takes nullable value class") fun maybe(id: Uid?) {}
    @AILoadBearing(invariant = "param of value-class fun") fun paramOn(@AILoadBearing(invariant = "p") id: Uid) {}
}

@AILocked(reason = "top-level returns value class") fun makeUid(): Uid = Uid("m")
@AILocked(reason = "top-level returns nullable value class") fun maybeUid(): Uid? = null
@AILocked(reason = "top-level extension on value class") fun Uid.shown(): String = raw
@AILocked(reason = "top-level takes value class") fun takeTop(id: Uid) {}
@AILocked(reason = "top-level takes UInt") fun unsigned(u: UInt) {}
@AILocked(reason = "top-level takes Duration") fun wait(d: kotlin.time.Duration) {}
