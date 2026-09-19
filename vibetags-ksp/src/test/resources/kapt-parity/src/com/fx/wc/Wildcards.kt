package com.fx.wc

import se.deversity.vibetags.annotations.AILocked

open class Base
class Leaf : Base()
@JvmInline value class Eid(val raw: String)

class Wild {
    @AILocked(reason = "default wildcards") fun plain(a: List<Base>, b: Comparable<Leaf>) {}
    @AILocked(reason = "suppressed on the type") fun onType(a: @JvmSuppressWildcards List<Base>) {}
    @AILocked(reason = "suppressed on an argument") fun onArgument(a: Map<String, @JvmSuppressWildcards List<Base>>) {}
    @AILocked(reason = "suppressed on the function") @JvmSuppressWildcards fun onFunction(a: List<Base>, b: Comparable<Leaf>) {}
    @AILocked(reason = "suppression turned off") @JvmSuppressWildcards(false) fun turnedOff(a: List<Base>) {}
    @AILocked(reason = "forced on a final argument") fun forced(a: List<@JvmWildcard Leaf>) {}
    @AILocked(reason = "forced and suppressed") fun both(a: @JvmSuppressWildcards List<@JvmWildcard Leaf>) {}
}

@JvmSuppressWildcards
class Quiet {
    @AILocked(reason = "suppressed on the class") fun inClass(a: List<Base>) {}
    @AILocked(reason = "class suppression overridden") @JvmSuppressWildcards(false) fun overridden(a: List<Base>) {}
}

class Boxing {
    @OptIn(ExperimentalStdlibApi::class)
    @AILocked(reason = "exposed boxed fun") @JvmExposeBoxed fun exposed(id: Eid) {}

    @OptIn(ExperimentalStdlibApi::class)
    @AILocked(reason = "exposed boxed, renamed") @JvmExposeBoxed("exposedAs") fun renamed(id: Eid) {}

    @OptIn(ExperimentalStdlibApi::class)
    @AILocked(reason = "exposed boxed ctor") @JvmExposeBoxed constructor(id: Eid)

    @AILocked(reason = "plain ctor") constructor(n: Int)
}

@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
@JvmInline value class Exposed @AILocked(reason = "ctor of an exposed value class") constructor(val raw: String) {
    @AILocked(reason = "member of an exposed value class") fun member(): Int = 1
    @AILocked(reason = "member of an exposed value class taking one") fun with(other: Eid): Int = 1
    @get:AILocked(reason = "getter of an exposed value class") val twice: Int get() = 2
}

@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
class ExposedHolder {
    @AILocked(reason = "value-class fun in an exposed class") fun take(id: Eid) {}
    @AILocked(reason = "plain fun in an exposed class") fun plain(n: Int) {}
    @get:AILocked(reason = "value-class getter in an exposed class") val current: Eid get() = Eid("c")
}
