package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.FileLocation;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyGetter;
import com.google.devtools.ksp.symbol.KSPropertySetter;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.KSValueParameter;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.SourceLocation;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the stub model: what kapt's generated Java stubs would declare for a set of Kotlin files,
 * with each annotation on the stub element kapt would have put it on.
 *
 * <p>Each rule below is one observed kapt behaviour, pinned by {@code StubParityTest} against paths
 * recorded from a real kapt build of the same fixture:
 * <ul>
 *   <li>Top-level functions and properties live on a file facade ({@code FileKt}, or the name
 *       {@code @file:JvmName} gives it).</li>
 *   <li>A companion object's fields live on the outer class; a {@code @JvmStatic} companion member
 *       exists twice, on the companion and statically on the outer class.</li>
 *   <li>{@code @JvmOverloads} produces one copy per omitted trailing default, each annotated.</li>
 *   <li>An interface member with a body has a static copy on {@code DefaultImpls}, taking the
 *       interface as its first parameter, unless {@code -jvm-default} disables compatibility.</li>
 *   <li>A property annotation with no use-site target lands on the backing field when the
 *       annotation may target a field; on a constructor {@code val} it also lands on the
 *       constructor parameter when it may target one, and on a data class's {@code copy()}
 *       parameter (Kotlin 2.2's {@code param-property} default).</li>
 *   <li>{@code @get:}, {@code @set:} and {@code @setparam:} land on the accessor, and on the
 *       setter's parameter, which kapt names {@code p0}.</li>
 *   <li>An {@code inline} function with a {@code reified} type parameter, and a function whose JVM
 *       name is mangled (a value-class parameter), have no stub at all.</li>
 *   <li>{@code @JvmExposeBoxed} (on the function, constructor or property, or on its class) gives a
 *       mangled declaration a stub after all: under its {@code jvmName} or its own name, with the
 *       value class boxed in the signature.</li>
 *   <li>{@code @JvmSuppressWildcards} on a class, function or property applies to every parameter
 *       type beneath it, the nearest one winning ({@link JvmTypes} handles type and argument uses).</li>
 * </ul>
 */
final class StubBuilder {

    private static final String JVM_STATIC = "kotlin.jvm.JvmStatic";
    private static final String JVM_FIELD = "kotlin.jvm.JvmField";
    private static final String JVM_OVERLOADS = "kotlin.jvm.JvmOverloads";
    private static final String JVM_NAME = "kotlin.jvm.JvmName";
    private static final String JVM_EXPOSE_BOXED = "kotlin.jvm.JvmExposeBoxed";

    private final Resolver resolver;
    private final AnnotationReader annotations;
    private final boolean defaultImpls;
    private final boolean paramProperty;
    private final Map<String, KTypeElement> declared = new LinkedHashMap<>();
    private final JvmTypes types = new JvmTypes(declared);
    private final Map<String, KPackageElement> packages = new HashMap<>();
    private final Map<KSFunctionDeclaration, List<KExecutableElement>> methods = new HashMap<>();
    private final Map<KTypeElement, KTypeElement> defaultImplsOf = new HashMap<>();
    private final List<KTypeElement> roots = new ArrayList<>();
    private final List<String> dropped = new ArrayList<>();
    private final Map<String, Optional<KotlinExtent>> extents = new HashMap<>();

    /**
     * @param defaultImpls whether interfaces get a {@code DefaultImpls} class, which every
     *                     {@code -jvm-default} mode except {@code no-compatibility} (and its old
     *                     name {@code all}) produces
     */
    StubBuilder(Resolver resolver, AnnotationReader annotations, boolean defaultImpls) {
        this(resolver, annotations, defaultImpls, true);
    }

    /**
     * @param paramProperty whether a constructor {@code val}'s annotation with no use-site target
     *                      lands on the field as well as the parameter: Kotlin 2.2's
     *                      {@code param-property} default. {@code false} is {@code first-only}, the
     *                      default before 2.2 and what {@code -Xannotation-default-target=first-only}
     *                      restores: the parameter alone when the annotation may target one.
     */
    StubBuilder(Resolver resolver, AnnotationReader annotations, boolean defaultImpls, boolean paramProperty) {
        this.resolver = resolver;
        this.annotations = annotations;
        this.defaultImpls = defaultImpls;
        this.paramProperty = paramProperty;
    }

    /** Builds the model for {@code files}, in the order given. */
    StubModel build(Iterable<KSFile> files) {
        for (KSFile file : files) {
            buildFile(file);
        }
        linkOverrides();
        return new StubModel(roots, declared, dropped);
    }

    /** The members of one declaring type, and where the members kapt relocates end up. */
    private record Owner(KTypeElement type, JvmTypes.Scope scope, Kind kind,
                         @Nullable KTypeElement outer, boolean valueClass,
                         boolean suppressWildcards, boolean exposeBoxed) {
        enum Kind { FACADE, CLASS, INTERFACE, OBJECT, COMPANION }

        boolean isStaticContext() {
            return kind == Kind.FACADE || kind == Kind.OBJECT;
        }
    }

    private void buildFile(KSFile file) {
        String packageName = file.getPackageName().asString();
        KPackageElement pkg = packages.computeIfAbsent(packageName, KPackageElement::new);
        Path source = Paths.get(file.getFilePath());
        KTypeElement facade = null;
        List<KTypeElement> fileRoots = new ArrayList<>();
        for (KSDeclaration declaration : list(file.getDeclarations().iterator())) {
            if (declaration instanceof KSClassDeclaration cls) {
                fileRoots.add(buildClass(cls, pkg, source, JvmTypes.Scope.empty(), false));
            } else if (declaration instanceof KSFunctionDeclaration || declaration instanceof KSPropertyDeclaration) {
                if (facade == null) {
                    facade = facade(declaration, pkg, file, source);
                }
                Owner owner = new Owner(facade, JvmTypes.Scope.empty(), Owner.Kind.FACADE, null, false,
                    false, false);
                if (declaration instanceof KSFunctionDeclaration function) {
                    function(function, owner);
                } else {
                    property((KSPropertyDeclaration) declaration, owner, false);
                }
            }
        }
        if (facade != null) {
            fileRoots.add(facade);
        }
        roots.addAll(fileRoots);
    }

    private KTypeElement facade(KSDeclaration first, KPackageElement pkg, KSFile file, Path source) {
        String owner = first instanceof KSFunctionDeclaration function
            ? resolver.getOwnerJvmClassName(function)
            : resolver.getOwnerJvmClassName((KSPropertyDeclaration) first);
        if (owner == null) {
            String fileName = file.getFileName();
            String stem = fileName.endsWith(".kt") ? fileName.substring(0, fileName.length() - 3) : fileName;
            String prefix = pkg.isUnnamed() ? "" : pkg.getQualifiedName() + ".";
            owner = prefix + stem.substring(0, 1).toUpperCase(Locale.ROOT) + stem.substring(1) + "Kt";
        }
        String qualified = owner.replace('/', '.');
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        KTypeElement facade = pkg.adopt(new KTypeElement(ElementKind.CLASS, simple, qualified,
            NestingKind.TOP_LEVEL, EnumSet.of(Modifier.PUBLIC, Modifier.FINAL), source));
        declared.put(qualified, facade);
        return facade;
    }

    private KTypeElement buildClass(KSClassDeclaration cls, KElement parent, Path source, JvmTypes.Scope outerScope,
                                    boolean outerSuppressWildcards) {
        ElementKind kind = switch (cls.getClassKind()) {
            case INTERFACE -> ElementKind.INTERFACE;
            case ENUM_CLASS -> ElementKind.ENUM;
            case ANNOTATION_CLASS -> ElementKind.ANNOTATION_TYPE;
            default -> ElementKind.CLASS;
        };
        String simple = cls.getSimpleName().asString();
        String qualified;
        NestingKind nesting;
        if (parent instanceof KTypeElement outerType) {
            qualified = outerType.getQualifiedName() + "." + simple;
            nesting = NestingKind.MEMBER;
        } else {
            KPackageElement pkg = (KPackageElement) parent;
            qualified = pkg.isUnnamed() ? simple : pkg.getQualifiedName() + "." + simple;
            nesting = NestingKind.TOP_LEVEL;
        }
        KTypeElement type = parent.adopt(new KTypeElement(kind, simple, qualified, nesting, javaModifiers(cls), source));
        declared.put(qualified, type);
        type.locate(locate(cls, false));
        // TYPE covers annotation types too, which is how Java reads it.
        place(annotations.read(cls), type, ElementType.TYPE);

        Map<String, KTypeParameterElement> parameters = new LinkedHashMap<>();
        for (KSTypeParameter parameter : cls.getTypeParameters()) {
            KTypeParameterElement element = new KTypeParameterElement(parameter.getName().asString(), type);
            type.addTypeParameter(element);
            parameters.put(element.getSimpleName().toString(), element);
        }
        JvmTypes.Scope scope = new JvmTypes.Scope(parameters, outerScope);
        recordSupertypes(cls, type);

        Owner.Kind ownerKind;
        if (cls.isCompanionObject()) {
            ownerKind = Owner.Kind.COMPANION;
        } else if (cls.getClassKind() == ClassKind.OBJECT) {
            ownerKind = Owner.Kind.OBJECT;
        } else if (cls.getClassKind() == ClassKind.INTERFACE) {
            ownerKind = Owner.Kind.INTERFACE;
        } else {
            ownerKind = Owner.Kind.CLASS;
        }
        Boolean suppressed = JvmTypes.suppression(cls);
        Owner owner = new Owner(type, scope, ownerKind,
            parent instanceof KTypeElement outerType ? outerType : null, isValueClass(cls),
            suppressed != null ? suppressed : outerSuppressWildcards, hasAnnotation(cls, JVM_EXPOSE_BOXED));

        KSFunctionDeclaration primary = cls.getPrimaryConstructor();
        List<KSDeclaration> members = list(cls.getDeclarations().iterator());
        Set<String> constructorProperties = constructorProperties(primary);
        Map<String, List<AnnotationReader.Use>> propertyUses = new HashMap<>();
        for (KSDeclaration member : members) {
            if (member instanceof KSPropertyDeclaration property
                    && constructorProperties.contains(property.getSimpleName().asString())) {
                propertyUses.put(property.getSimpleName().asString(), annotations.read(property));
            }
        }

        KExecutableElement primaryElement = null;
        if (primary != null && kind != ElementKind.INTERFACE && kind != ElementKind.ANNOTATION_TYPE) {
            primaryElement = constructor(primary, owner, propertyUses);
        }
        for (KSDeclaration member : members) {
            if (member instanceof KSClassDeclaration nested) {
                if (nested.getClassKind() == ClassKind.ENUM_ENTRY) {
                    enumConstant(nested, type);
                } else {
                    buildClass(nested, type, source, scope, owner.suppressWildcards());
                }
            } else if (member instanceof KSFunctionDeclaration function) {
                if (UtilsKt.isConstructor(function)) {
                    if (!function.equals(primary)) {
                        constructor(function, owner, Map.of());
                    }
                } else {
                    function(function, owner);
                }
            } else if (member instanceof KSPropertyDeclaration property) {
                property(property, owner, constructorProperties.contains(property.getSimpleName().asString()));
            }
        }
        if (primaryElement != null && cls.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.DATA)) {
            copyMethod(primaryElement, owner);
        }
        return type;
    }

    private void recordSupertypes(KSClassDeclaration cls, KTypeElement type) {
        try {
            Iterator<KSType> supertypes = UtilsKt.getAllSuperTypes(cls).iterator();
            while (supertypes.hasNext()) {
                type.addSupertype(JvmTypes.javaName(JvmTypes.qualifiedName(supertypes.next().getDeclaration())));
            }
        } catch (RuntimeException unresolved) {
            // A supertype that does not resolve leaves the set short; only the locked-override
            // check reads it, and a missed override there costs one advisory warning.
        }
    }

    private static Set<String> constructorProperties(@Nullable KSFunctionDeclaration primary) {
        Set<String> names = new HashSet<>();
        if (primary != null) {
            for (KSValueParameter parameter : primary.getParameters()) {
                com.google.devtools.ksp.symbol.KSName name = parameter.getName();
                if ((parameter.isVal() || parameter.isVar()) && name != null) {
                    names.add(name.asString());
                }
            }
        }
        return names;
    }

    private void enumConstant(KSClassDeclaration entry, KTypeElement enumType) {
        KVariableElement constant = enumType.adopt(new KVariableElement(ElementKind.ENUM_CONSTANT,
            entry.getSimpleName().asString(), EnumSet.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL),
            enumType.asType()));
        constant.locate(locate(entry, true));
        place(annotations.read(entry), constant, ElementType.FIELD);
    }

    private @Nullable KExecutableElement constructor(KSFunctionDeclaration function, Owner owner,
                                                     Map<String, List<AnnotationReader.Use>> propertyUses) {
        List<KSValueParameter> parameters = function.getParameters();
        boolean exposed = owner.exposeBoxed() || hasAnnotation(function, JVM_EXPOSE_BOXED);
        if ((owner.valueClass() || takesValueClass(parameters, null)) && !exposed) {
            noteDropped(annotations.read(function), "constructor of " + owner.type().getQualifiedName(),
                "a value class compiles its constructors to a synthetic pair, which has no Java stub;"
                    + " @JvmExposeBoxed gives it one");
            return null;
        }
        Sig sig = new Sig(suppressWildcards(function, owner), exposed);
        boolean varArgs = !parameters.isEmpty() && parameters.get(parameters.size() - 1).isVararg();
        KExecutableElement constructor = owner.type().adopt(new KExecutableElement(ElementKind.CONSTRUCTOR,
            "<init>", javaModifiers(function), varArgs, false));
        constructor.locate(locate(function, false));
        place(annotations.read(function), constructor, ElementType.CONSTRUCTOR);
        for (int i = 0; i < parameters.size(); i++) {
            KSValueParameter parameter = parameters.get(i);
            KVariableElement element = constructor.addParameter(parameter(parameter, i, owner.scope(), sig));
            placeParameter(annotations.read(parameter), element);
            com.google.devtools.ksp.symbol.KSName declared = parameter.getName();
            String name = declared == null ? "" : declared.asString();
            for (AnnotationReader.Use use : propertyUses.getOrDefault(name, List.of())) {
                if (use.target() == AnnotationUseSiteTarget.PARAM
                        || use.target() == null && annotations.allows(use.data().type(), ElementType.PARAMETER)) {
                    element.annotate(use.data());
                }
            }
        }
        overloads(function, constructor, owner.type(), 0);
        return constructor;
    }

    /** A data class's {@code copy}, whose parameters carry the constructor parameters' annotations. */
    private void copyMethod(KExecutableElement primary, Owner owner) {
        KExecutableElement copy = owner.type().adopt(new KExecutableElement(ElementKind.METHOD, "copy",
            EnumSet.of(Modifier.PUBLIC, Modifier.FINAL), false, false));
        copy.setReturnType(owner.type().asType());
        copy.locate(primary.location());
        for (KVariableElement parameter : primary.parameters()) {
            KVariableElement element = copy.addParameter(new KVariableElement(ElementKind.PARAMETER,
                parameter.getSimpleName().toString(), Set.of(), parameter.asType()));
            for (AnnotationData annotation : parameter.annotations()) {
                element.annotate(annotation);
            }
        }
    }

    private void function(KSFunctionDeclaration function, Owner owner) {
        if (isInlineReified(function)) {
            noteDropped(function, owner, "it is inline with a reified type parameter, which has no Java stub");
            return;
        }
        boolean mangled = !hasAnnotation(function, JVM_NAME) && manglesName(function, owner);
        boolean exposed = mangled && (owner.exposeBoxed() || hasAnnotation(function, JVM_EXPOSE_BOXED));
        if (mangled && !exposed) {
            // Mangled for a value class: not a Java identifier, so kapt writes no stub.
            noteDropped(function, owner, "a value class in its signature mangles its JVM name, which has no Java stub;"
                + " give it a @JvmName or @JvmExposeBoxed, or move the guardrail to the class");
            return;
        }
        String name = exposed ? exposedName(function) : jvmName(function);
        int suffix = name.indexOf('-');
        if (suffix >= 0) {
            // KSP reports a mangled name where the compiler writes a plain one (kotlin.Result is
            // exempt from mangling; a top-level function returning a value class is not mangled).
            name = name.substring(0, suffix);
        }
        Sig sig = new Sig(suppressWildcards(function, owner), exposed);
        boolean isAbstract = function.isAbstract();
        Set<Modifier> modifiers = javaModifiers(function);
        if (owner.kind() == Owner.Kind.FACADE) {
            modifiers.add(Modifier.STATIC);
        }
        boolean isDefault = owner.kind() == Owner.Kind.INTERFACE && !isAbstract;
        KExecutableElement method = method(function, name, owner.type(), owner.scope(), modifiers, isDefault, null, sig);
        methods.computeIfAbsent(function, key -> new ArrayList<>()).add(method);
        overloads(function, method, owner.type(), receiverCount(function));

        KTypeElement outer = owner.outer();
        if (owner.kind() == Owner.Kind.COMPANION && outer != null && hasAnnotation(function, JVM_STATIC)) {
            Set<Modifier> statics = javaModifiers(function);
            statics.add(Modifier.STATIC);
            method(function, name, outer, owner.scope(), statics, false, null, sig);
        }
        if (isDefault && defaultImpls) {
            method(function, name, defaultImpls(owner.type()), owner.scope(),
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), false, owner.type().asType(), sig);
        }
    }

    private KExecutableElement method(KSFunctionDeclaration function, String name, KTypeElement target,
                                      JvmTypes.Scope outerScope, Set<Modifier> modifiers, boolean isDefault,
                                      @Nullable TypeMirror self, Sig sig) {
        boolean suspend = function.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.SUSPEND);
        List<KSValueParameter> parameters = function.getParameters();
        boolean varArgs = !suspend && !parameters.isEmpty() && parameters.get(parameters.size() - 1).isVararg();
        KExecutableElement method = target.adopt(new KExecutableElement(ElementKind.METHOD, name, modifiers,
            varArgs, isDefault));
        method.locate(locate(function, false));
        Map<String, KTypeParameterElement> typeParameters = new LinkedHashMap<>();
        for (KSTypeParameter parameter : function.getTypeParameters()) {
            KTypeParameterElement element = new KTypeParameterElement(parameter.getName().asString(), method);
            method.addTypeParameter(element);
            typeParameters.put(element.getSimpleName().toString(), element);
        }
        JvmTypes.Scope scope = new JvmTypes.Scope(typeParameters, outerScope);
        method.setReturnType(suspend
            ? types.declared("java.lang.Object", List.of())
            : types.render(function.getReturnType(), JvmTypes.Position.RETURN, scope));

        if (self != null) {
            method.addParameter(new KVariableElement(ElementKind.PARAMETER, "$this", Set.of(), self));
        }
        KSTypeReference receiver = function.getExtensionReceiver();
        if (receiver != null) {
            method.addParameter(new KVariableElement(ElementKind.PARAMETER,
                "$this$" + function.getSimpleName().asString(), Set.of(),
                types.render(receiver, JvmTypes.Position.PARAMETER, scope, sig.suppressWildcards(), sig.boxed())));
        }
        for (int i = 0; i < parameters.size(); i++) {
            KVariableElement element = method.addParameter(parameter(parameters.get(i), i, scope, sig));
            placeParameter(annotations.read(parameters.get(i)), element);
        }
        if (suspend) {
            TypeMirror result = types.render(function.getReturnType(), JvmTypes.Position.ARGUMENT, scope);
            method.addParameter(new KVariableElement(ElementKind.PARAMETER, "$completion", Set.of(),
                types.declared("kotlin.coroutines.Continuation", List.of(new KTypeMirror.Wildcard(null, result)))));
        }
        place(annotations.read(function), method, ElementType.METHOD);
        return method;
    }

    /**
     * What decides how a declaration's parameter types are spelled: whether
     * {@code @JvmSuppressWildcards} is in force (its own, or the nearest enclosing class's), and
     * whether it is the boxed variant {@code @JvmExposeBoxed} exposes.
     */
    private record Sig(boolean suppressWildcards, boolean boxed) {
    }

    private static boolean suppressWildcards(KSDeclaration declaration, Owner owner) {
        Boolean own = JvmTypes.suppression(declaration);
        return own != null ? own : owner.suppressWildcards();
    }

    /** The name {@code @JvmExposeBoxed} gives its variant: its {@code jvmName}, or the declared name. */
    private static String exposedName(KSFunctionDeclaration function) {
        Iterator<com.google.devtools.ksp.symbol.KSAnnotation> it = function.getAnnotations().iterator();
        while (it.hasNext()) {
            com.google.devtools.ksp.symbol.KSAnnotation annotation = it.next();
            KSType type = annotation.getAnnotationType().resolve();
            com.google.devtools.ksp.symbol.KSName name = type.getDeclaration().getQualifiedName();
            if (name != null && JVM_EXPOSE_BOXED.equals(name.asString())) {
                for (com.google.devtools.ksp.symbol.KSValueArgument argument : annotation.getArguments()) {
                    if (argument.getValue() instanceof String given && !given.isEmpty()) {
                        return given;
                    }
                }
            }
        }
        return function.getSimpleName().asString();
    }

    private KVariableElement parameter(KSValueParameter parameter, int index, JvmTypes.Scope scope, Sig sig) {
        com.google.devtools.ksp.symbol.KSName declared = parameter.getName();
        String name = declared == null ? "p" + index : declared.asString();
        TypeMirror type = types.render(parameter.getType(), JvmTypes.Position.PARAMETER, scope,
            sig.suppressWildcards(), sig.boxed());
        if (parameter.isVararg()) {
            type = new KTypeMirror.Arr(type);
        }
        return new KVariableElement(ElementKind.PARAMETER, name, Set.of(), type);
    }

    /**
     * {@code @JvmOverloads}: one more copy of {@code full} for each defaulted parameter, dropping
     * them from the last one backwards, each carrying the same annotations.
     */
    private void overloads(KSFunctionDeclaration function, KExecutableElement full, KTypeElement target,
                           int offset) {
        if (!hasAnnotation(function, JVM_OVERLOADS)) {
            return;
        }
        List<Integer> defaulted = new ArrayList<>();
        List<KSValueParameter> parameters = function.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).getHasDefault()) {
                defaulted.add(offset + i);
            }
        }
        for (int dropped = 1; dropped <= defaulted.size(); dropped++) {
            Set<Integer> omit = new HashSet<>(defaulted.subList(defaulted.size() - dropped, defaulted.size()));
            KExecutableElement copy = target.adopt(new KExecutableElement(full.getKind(),
                full.getSimpleName().toString(), full.getModifiers(), false, full.isDefault()));
            copy.setReturnType(full.getReturnType());
            copy.locate(full.location());
            List<KVariableElement> kept = full.parameters();
            for (int i = 0; i < kept.size(); i++) {
                if (!omit.contains(i)) {
                    KVariableElement source = kept.get(i);
                    KVariableElement element = copy.addParameter(new KVariableElement(ElementKind.PARAMETER,
                        source.getSimpleName().toString(), Set.of(), source.asType()));
                    source.annotations().forEach(element::annotate);
                }
            }
            full.annotations().forEach(copy::annotate);
        }
    }

    private static int receiverCount(KSFunctionDeclaration function) {
        return function.getExtensionReceiver() == null ? 0 : 1;
    }

    private void property(KSPropertyDeclaration property, Owner owner, boolean constructorProperty) {
        String name = property.getSimpleName().asString();
        List<AnnotationReader.Use> uses = annotations.read(property);
        KSPropertyGetter getter = property.getGetter();
        KSPropertySetter setter = property.getSetter();
        var kotlinModifiers = property.getModifiers();
        boolean isConst = kotlinModifiers.contains(com.google.devtools.ksp.symbol.Modifier.CONST);
        boolean jvmField = hasAnnotation(property, JVM_FIELD);
        boolean isPrivate = kotlinModifiers.contains(com.google.devtools.ksp.symbol.Modifier.PRIVATE);

        List<AnnotationReader.Use> fieldUses = new ArrayList<>();
        List<AnnotationReader.Use> getterUses = new ArrayList<>();
        List<AnnotationReader.Use> setterUses = new ArrayList<>();
        List<AnnotationReader.Use> setParamUses = new ArrayList<>();
        for (AnnotationReader.Use use : uses) {
            AnnotationUseSiteTarget target = use.target();
            if (target == null) {
                String type = use.data().type();
                // first-only: the constructor parameter already took it (the constructor places it).
                boolean parameterTookIt = constructorProperty && !paramProperty
                    && annotations.allows(type, ElementType.PARAMETER);
                if (annotations.allows(type, ElementType.FIELD) && !parameterTookIt) {
                    fieldUses.add(use);
                }
            } else {
                switch (target) {
                    case FIELD -> fieldUses.add(use);
                    case GET -> getterUses.add(use);
                    case SET -> setterUses.add(use);
                    case SETPARAM -> setParamUses.add(use);
                    default -> { } // PROPERTY is Kotlin-only; PARAM is placed by the constructor
                }
            }
        }
        if (getter != null) {
            getterUses.addAll(annotations.read(getter));
        }
        if (setter != null) {
            setterUses.addAll(annotations.read(setter));
            setParamUses.addAll(annotations.read(setter.getParameter()));
        }

        boolean hasField = property.getHasBackingField() || property.isDelegated();
        if (hasField && owner.kind() != Owner.Kind.INTERFACE) {
            KTypeElement outer = owner.outer();
            boolean hoisted = owner.kind() == Owner.Kind.COMPANION && outer != null
                && (outer.getKind() != ElementKind.INTERFACE || isConst || jvmField);
            KTypeElement fieldOwner = hoisted && outer != null ? outer : owner.type();
            Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
            modifiers.add(isConst || jvmField ? Modifier.PUBLIC : Modifier.PRIVATE);
            if (hoisted || owner.isStaticContext() || isConst) {
                modifiers.add(Modifier.STATIC);
            }
            if (!property.isMutable()) {
                modifiers.add(Modifier.FINAL);
            }
            String fieldName = property.isDelegated() ? name + "$delegate" : name;
            SourceLocation where = locate(property, constructorProperty);
            KVariableElement field = fieldOwner.adopt(new KVariableElement(ElementKind.FIELD, fieldName,
                modifiers, types.render(property.getType(), JvmTypes.Position.FIELD, owner.scope())));
            field.locate(where);
            fieldUses.forEach(use -> field.annotate(use.data()));
        }

        if (isConst || jvmField || isPrivate) {
            return; // no accessors: a const, a @JvmField, or a private property read directly
        }
        boolean isAbstract = UtilsKt.isAbstract(property);
        boolean jvmStatic = hasAnnotation(property, JVM_STATIC);
        boolean exposed = owner.exposeBoxed() || hasAnnotation(property, JVM_EXPOSE_BOXED);
        boolean receiverIsValue = !exposed && isValueClass(property.getExtensionReceiver());
        boolean typeIsValue = !exposed && isValueClass(property.getType());
        Sig sig = new Sig(suppressWildcards(property, owner), exposed);
        String where = " of property " + name + " in " + owner.type().getQualifiedName();
        String why = "a value class in its signature mangles its JVM name, which has no Java stub";
        if (getter != null) {
            if (owner.valueClass() && !exposed || receiverIsValue || typeIsValue && owner.kind() != Owner.Kind.FACADE) {
                noteDropped(getterUses, "the getter" + where, why);
            } else {
                String getterName = unmangled(accessorName(getter, "get", name));
                accessor(property, getterName, owner, isAbstract, jvmStatic, null, getterUses, List.of(), sig);
            }
        }
        if (property.isMutable() && setter != null
                && !setter.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.PRIVATE)) {
            if (owner.valueClass() && !exposed || receiverIsValue || typeIsValue) {
                noteDropped(setterUses, "the setter" + where, why);
                noteDropped(setParamUses, "the setter parameter" + where, why);
            } else {
                String setterName = unmangled(accessorName(setter, "set", name));
                accessor(property, setterName, owner, isAbstract, jvmStatic,
                    types.render(property.getType(), JvmTypes.Position.PARAMETER, owner.scope(),
                        sig.suppressWildcards(), sig.boxed()), setterUses, setParamUses, sig);
            }
        }
    }

    /** {@code name} without the {@code -suffix} KSP reports for a variant the compiler writes plainly. */
    private static String unmangled(String name) {
        int suffix = name.indexOf('-');
        return suffix < 0 ? name : name.substring(0, suffix);
    }

    private String accessorName(com.google.devtools.ksp.symbol.KSPropertyAccessor accessor, String prefix,
                                String property) {
        String name = null;
        try {
            name = resolver.getJvmName(accessor);
        } catch (RuntimeException unresolved) {
            // Fall through to the conventional name.
        }
        if (name == null || name.isEmpty()) {
            name = prefix + property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
        }
        return name;
    }

    /**
     * A getter (when {@code value} is null) or a setter taking {@code value} as {@code p0}, on the
     * property's owner, plus its {@code @JvmStatic} and {@code DefaultImpls} copies.
     */
    private void accessor(KSPropertyDeclaration property, String name, Owner owner, boolean isAbstract,
                          boolean jvmStatic, @Nullable TypeMirror value, List<AnnotationReader.Use> uses,
                          List<AnnotationReader.Use> parameterUses, Sig sig) {
        if (name.indexOf('-') >= 0) {
            return;
        }
        Set<Modifier> modifiers = EnumSet.of(Modifier.PUBLIC);
        if (owner.kind() == Owner.Kind.FACADE) {
            modifiers.add(Modifier.STATIC);
        }
        if (isAbstract) {
            modifiers.add(Modifier.ABSTRACT);
        }
        boolean isDefault = owner.kind() == Owner.Kind.INTERFACE && !isAbstract;
        accessorOn(owner.type(), property, name, modifiers, isDefault, null, value, owner.scope(), uses,
            parameterUses, sig);
        KTypeElement outer = owner.outer();
        if (owner.kind() == Owner.Kind.COMPANION && outer != null && jvmStatic) {
            accessorOn(outer, property, name, EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), false, null,
                value, owner.scope(), uses, parameterUses, sig);
        }
        if (isDefault && defaultImpls) {
            accessorOn(defaultImpls(owner.type()), property, name, EnumSet.of(Modifier.PUBLIC, Modifier.STATIC),
                false, owner.type().asType(), value, owner.scope(), uses, parameterUses, sig);
        }
    }

    private void accessorOn(KTypeElement target, KSPropertyDeclaration property, String name, Set<Modifier> modifiers,
                            boolean isDefault, @Nullable TypeMirror self, @Nullable TypeMirror value,
                            JvmTypes.Scope scope, List<AnnotationReader.Use> uses,
                            List<AnnotationReader.Use> parameterUses, Sig sig) {
        KExecutableElement method = target.adopt(new KExecutableElement(ElementKind.METHOD, name, modifiers,
            false, isDefault));
        method.locate(locate(property, false));
        method.setReturnType(value == null
            ? types.render(property.getType(), JvmTypes.Position.RETURN, scope)
            : KTypeMirror.NoneType.VOID);
        if (self != null) {
            method.addParameter(new KVariableElement(ElementKind.PARAMETER, "$this", Set.of(), self));
        }
        KSTypeReference receiver = property.getExtensionReceiver();
        if (receiver != null) {
            method.addParameter(new KVariableElement(ElementKind.PARAMETER,
                "$this$" + property.getSimpleName().asString(), Set.of(),
                types.render(receiver, JvmTypes.Position.PARAMETER, scope, sig.suppressWildcards(), sig.boxed())));
        }
        if (value != null) {
            KVariableElement parameter = method.addParameter(
                new KVariableElement(ElementKind.PARAMETER, "p0", Set.of(), value));
            parameterUses.forEach(use -> parameter.annotate(use.data()));
        }
        uses.forEach(use -> method.annotate(use.data()));
    }

    private KTypeElement defaultImpls(KTypeElement iface) {
        return defaultImplsOf.computeIfAbsent(iface, owner -> {
            String qualified = owner.getQualifiedName() + ".DefaultImpls";
            KTypeElement impls = owner.adopt(new KTypeElement(ElementKind.CLASS, "DefaultImpls", qualified,
                NestingKind.MEMBER, EnumSet.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL), owner.sourceFile()));
            declared.put(qualified, impls);
            return impls;
        });
    }

    /**
     * Where {@code node} is written: the file KSP reports, from the first annotation line above its
     * declaration to where the declaration ends ({@link KotlinExtent}), or {@code null} for a
     * declaration with no file (a library symbol).
     *
     * @param listItem whether the declaration is a constructor property or an enum entry
     */
    private @Nullable SourceLocation locate(KSNode node, boolean listItem) {
        if (!(node.getLocation() instanceof FileLocation at)) {
            return null;
        }
        int line = at.getLineNumber();
        Optional<KotlinExtent> extent = extents.computeIfAbsent(at.getFilePath(), StubBuilder::readExtent);
        if (extent.isEmpty()) {
            return new SourceLocation(at.getFilePath(), line, line);
        }
        return new SourceLocation(at.getFilePath(), extent.get().start(line),
            Math.max(line, extent.get().end(line, listItem)));
    }

    private static Optional<KotlinExtent> readExtent(String file) {
        try {
            return Optional.of(new KotlinExtent(Files.readString(Path.of(file))));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty(); // a start line with no end is still a position
        }
    }

    /** Places annotations written with no use-site target, where the annotation may go. */
    private void place(List<AnnotationReader.Use> uses, KElement element, ElementType kind) {
        for (AnnotationReader.Use use : uses) {
            if (use.target() == null && annotations.allows(use.data().type(), kind)) {
                element.annotate(use.data());
            }
        }
    }

    private void placeParameter(List<AnnotationReader.Use> uses, KElement element) {
        for (AnnotationReader.Use use : uses) {
            if (use.target() == AnnotationUseSiteTarget.PARAM
                    || use.target() == null && annotations.allows(use.data().type(), ElementType.PARAMETER)) {
                element.annotate(use.data());
            }
        }
    }

    private boolean hasAnnotation(KSAnnotated annotated, String type) {
        for (AnnotationReader.Use use : annotations.read(annotated)) {
            if (use.data().type().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a VibeTags annotation on a function that gets no stub element. kapt drops these
     * silently; KSP keeps kapt's element set so paths stay the same across front ends, and says so.
     */
    private void noteDropped(KSFunctionDeclaration function, Owner owner, String why) {
        noteDropped(annotations.read(function),
            "fun " + function.getSimpleName().asString() + " in " + owner.type().getQualifiedName(), why);
    }

    private void noteDropped(List<AnnotationReader.Use> uses, String what, String why) {
        for (AnnotationReader.Use use : uses) {
            String type = use.data().type();
            if (type.startsWith("se.deversity.vibetags.annotations.")) {
                dropped.add("@" + type.substring(type.lastIndexOf('.') + 1) + " on " + what
                    + " reaches no guardrail file: " + why + ".");
            }
        }
    }

    /**
     * Whether the compiler mangles {@code function}'s JVM name for a value class, the case kapt
     * writes no stub for. Measured against kapt on Kotlin 2.4.10 ({@code StubParityTest}): a
     * member of a value class; a function whose receiver or any parameter, nullable or not, is a
     * value class; and a member (not a top-level function) that returns one. {@code kotlin.Result}
     * is exempt throughout, and a type argument ({@code List<Uid>}) never counts.
     */
    private static boolean manglesName(KSFunctionDeclaration function, Owner owner) {
        if (owner.valueClass() || takesValueClass(function.getParameters(), function.getExtensionReceiver())) {
            return true;
        }
        return owner.kind() != Owner.Kind.FACADE && isValueClass(function.getReturnType());
    }

    private static boolean takesValueClass(List<KSValueParameter> parameters, @Nullable KSTypeReference receiver) {
        if (isValueClass(receiver)) {
            return true;
        }
        for (KSValueParameter parameter : parameters) {
            if (isValueClass(parameter.getType())) { // a vararg of one is a compile error
                return true;
            }
        }
        return false;
    }

    private static boolean isValueClass(@Nullable KSTypeReference reference) {
        return reference != null && isValueClass(JvmTypes.expandAliases(reference.resolve()).getDeclaration());
    }

    /** A value class other than {@code kotlin.Result}, which the compiler never mangles for. */
    private static boolean isValueClass(KSDeclaration declaration) {
        return declaration instanceof KSClassDeclaration cls
            && !"kotlin.Result".equals(JvmTypes.qualifiedName(cls))
            && (cls.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.VALUE)
                || cls.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.INLINE));
    }

    private static boolean isInlineReified(KSFunctionDeclaration function) {
        if (!function.getModifiers().contains(com.google.devtools.ksp.symbol.Modifier.INLINE)) {
            return false;
        }
        for (KSTypeParameter parameter : function.getTypeParameters()) {
            if (parameter.isReified()) {
                return true;
            }
        }
        return false;
    }

    private String jvmName(KSFunctionDeclaration function) {
        String name = null;
        try {
            name = resolver.getJvmName(function);
        } catch (RuntimeException unresolved) {
            // Fall through to the declared name.
        }
        return name == null || name.isEmpty() ? function.getSimpleName().asString() : name;
    }

    /** The JVM modifiers KSP computes for {@code declaration}, as a mutable javax set. */
    private Set<Modifier> javaModifiers(KSDeclaration declaration) {
        Set<Modifier> result = EnumSet.noneOf(Modifier.class);
        Set<com.google.devtools.ksp.symbol.Modifier> modifiers;
        try {
            modifiers = resolver.effectiveJavaModifiers(declaration);
        } catch (RuntimeException unresolved) {
            modifiers = declaration.getModifiers();
        }
        for (com.google.devtools.ksp.symbol.Modifier modifier : modifiers) {
            switch (modifier) {
                case PUBLIC, INTERNAL -> result.add(Modifier.PUBLIC);
                case PRIVATE -> result.add(Modifier.PRIVATE);
                case PROTECTED -> result.add(Modifier.PROTECTED);
                case ABSTRACT -> result.add(Modifier.ABSTRACT);
                case FINAL -> result.add(Modifier.FINAL);
                case SEALED -> result.add(Modifier.SEALED);
                case JAVA_STATIC -> result.add(Modifier.STATIC);
                case JAVA_DEFAULT -> result.add(Modifier.DEFAULT);
                case JAVA_SYNCHRONIZED -> result.add(Modifier.SYNCHRONIZED);
                case JAVA_TRANSIENT -> result.add(Modifier.TRANSIENT);
                case JAVA_VOLATILE -> result.add(Modifier.VOLATILE);
                case JAVA_NATIVE -> result.add(Modifier.NATIVE);
                case JAVA_STRICT -> result.add(Modifier.STRICTFP);
                default -> { } // Kotlin-only (open, data, inline, ...): no JVM counterpart
            }
        }
        return result;
    }

    /** Resolves overriding while the round is live; the locked-override check reads it later. */
    private void linkOverrides() {
        for (Map.Entry<KSFunctionDeclaration, List<KExecutableElement>> entry : methods.entrySet()) {
            KSDeclaration overridee;
            try {
                overridee = entry.getKey().findOverridee();
            } catch (RuntimeException unresolved) {
                continue;
            }
            if (overridee instanceof KSFunctionDeclaration function) {
                List<KExecutableElement> targets = methods.get(function);
                if (targets != null && !targets.isEmpty()) {
                    for (KExecutableElement method : entry.getValue()) {
                        method.addOverridden(targets.get(0));
                    }
                }
            }
        }
    }

    private static <T> List<T> list(Iterator<T> iterator) {
        List<T> items = new ArrayList<>();
        iterator.forEachRemaining(items::add);
        return items;
    }
}
