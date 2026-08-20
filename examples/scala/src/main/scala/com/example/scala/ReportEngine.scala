package com.example.scala

import se.deversity.vibetags.annotations.AILocked

/**
 * The honest half of this example: a Java annotation on a Scala class compiles fine,
 * but scalac has no JSR 269 support, so the VibeTags processor NEVER SEES this class.
 * CI asserts that the generated files do not mention it. If you need a guardrail on
 * Scala code, put a thin annotated Java type next to it (see AuditLog.java) or write
 * the rule by hand outside the VIBETAGS markers.
 */
@AILocked(reason = "This reason never reaches a guardrail file — scalac does not run annotation processors.")
class ReportEngine {
  def render(reportId: String): String = s"report:$reportId"
}
