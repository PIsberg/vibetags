package com.fx.dt

import se.deversity.vibetags.annotations.AIInputSanitized
import se.deversity.vibetags.annotations.AILoadBearing
import se.deversity.vibetags.annotations.AILocked

class Props(
    @AILoadBearing(invariant = "param and field targets") val a: String,
    @AILocked(reason = "field target only") val b: String,
    @AIInputSanitized(AIInputSanitized.SanitizerType.SQL_INJECTION) val c: String,
    @param:AILoadBearing(invariant = "explicit param") val d: String,
    @field:AILoadBearing(invariant = "explicit field") val e: String,
)

data class DataProps(@AILoadBearing(invariant = "data class") val f: Int)

class NotCtor {
    @AILoadBearing(invariant = "non-constructor property") val g: String = ""
}
