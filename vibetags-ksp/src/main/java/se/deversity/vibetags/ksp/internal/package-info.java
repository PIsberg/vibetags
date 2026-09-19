/**
 * The adapter that lets {@code AIGuardrailProcessor} run under KSP.
 *
 * <p>KSP defines its own processor interface and cannot load a JSR 269 processor, so this package
 * does the reverse: it presents each Kotlin declaration as the {@code javax.lang.model} element
 * kapt's generated Java stub would have produced, and hands those elements to the unchanged
 * processor through a {@code ProcessingEnvironment} and {@code RoundEnvironment} built on KSP.
 *
 * <p>Two properties hold throughout:
 * <ul>
 *   <li><b>kapt-shaped, not Kotlin-shaped.</b> {@link se.deversity.vibetags.ksp.internal.StubBuilder}
 *       reproduces what kapt's stubs contain (facade classes, hoisted companion fields,
 *       {@code @JvmOverloads} copies, {@code DefaultImpls}, erased and mangled signatures) because
 *       element paths are identities: they key {@code .vibetags-locks}, granular rule filenames and
 *       the reactor sidecars. A project that moves from kapt to KSP must see no path change.</li>
 *   <li><b>Snapshots, not views.</b> Every element here is plain data built while KSP's round is
 *       live. The processor reads annotations after the last round closes, when a KSP
 *       {@code Resolver} is no longer valid, so nothing in this package keeps a KSP symbol.</li>
 * </ul>
 */
@NullMarked
package se.deversity.vibetags.ksp.internal;

import org.jspecify.annotations.NullMarked;
