package com.fx.nested

import se.deversity.vibetags.annotations.AILocked

@AILocked(reason = "facade default name") fun otherTop(x: Long) {}
@AILocked(reason = "private top") private fun privTop() {}
class Holder { @AILocked(reason = "local-ish") fun run() { } }
