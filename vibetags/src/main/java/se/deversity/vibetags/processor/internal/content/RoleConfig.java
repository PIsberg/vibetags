package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Role/topic-based routing for granular rule files, read from a {@code .vibetags-roles} config at
 * the VibeTags root (or a module root). Each role maps a human-readable name to one or more glob
 * patterns and/or fully-qualified names; annotated elements matching a role are grouped into a
 * single human-named rule file (e.g. {@code api-endpoints.md} scoping {@code **}{@code /*Controller.java})
 * instead of one file per class.
 *
 * <p>File format — one role per line, {@code #} comments and blank lines ignored:
 * <pre>
 * # name = comma-separated globs and/or fully-qualified names
 * api-endpoints     = **&#47;*Controller.java
 * database-models   = **&#47;*Entity.java
 * external-webhooks = **&#47;webhooks&#47;**, com.example.legacy.WeirdEndpoint
 * </pre>
 *
 * <p>An element is routed to the <em>first</em> role (config order) any of whose matchers match;
 * elements matching no role fall back to the per-class file. Matching is done against a path
 * reconstructed from the element's fully-qualified name, so it works even under non-javac or
 * in-memory compilation (no real source path required).
 */
public final class RoleConfig {

    /** Opt-in signal file: its presence at a root activates role-based granular routing there. */
    public static final String FILE_NAME = ".vibetags-roles";

    /** U+FEFF, which some editors save at the start of a UTF-8 file: encoding, not a role name. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final List<Role> roles;
    private final String contentHash;

    private RoleConfig(List<Role> roles, String contentHash) {
        this.roles = roles;
        this.contentHash = contentHash;
    }

    /**
     * Loads {@code <root>/.vibetags-roles} if present, else returns {@code null} (roles disabled —
     * granular writing keeps its per-class behavior). Unreadable files are treated as absent.
     */
    public static @Nullable RoleConfig load(Path root) {
        Path file = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        List<Role> parsed = new ArrayList<>();
        for (String raw : linesOf(content)) {
            Role role = Role.parse(raw.trim());
            if (role != null) {
                parsed.add(role);
            }
        }
        return new RoleConfig(parsed, ContentHash.of(content));
    }

    /**
     * {@code content}'s lines, after any leading byte order mark: left in, it became part of the
     * first role's name and split that role between two rule files (#1197).
     */
    private static String[] linesOf(String content) {
        String text = content.isEmpty() || content.charAt(0) != BYTE_ORDER_MARK ? content : content.substring(1);
        return text.split("\\r\\n|\\r|\\n", -1);
    }

    /**
     * Memoised {@link #roleFor} answers, keyed by the two values the match actually reads.
     *
     * <p>Not keyed by {@link TaggedElement} itself: its {@code equals} is {@code (path, kind)}
     * while the match reads {@code (qualifiedName, kind)}. Those agree for every element the
     * processor builds today, but a memo keyed on something other than its function's inputs
     * answers the wrong question the moment they stop agreeing, and silently.
     *
     * <p>Concurrent because this object is handed to the renderers and nothing in its contract says
     * they are sequential. They are today — the parallel phase writes content that was already
     * rendered — so this keeps that a performance decision rather than a correctness one. Bounded
     * by one build's elements, and the keys are strings, so nothing here holds a javac
     * {@code Element}.
     */
    private final Map<String, Optional<String>> roleMemo = new ConcurrentHashMap<>();

    /** True when there are no usable roles (parsing produced nothing) — treat as roles-off. */
    public boolean isEmpty() {
        return roles.isEmpty();
    }

    /** 8-hex hash of the raw config content, for folding into the build fingerprint. */
    public String contentHash() {
        return contentHash;
    }

    /**
     * The first role (config order) whose glob or FQN matchers match {@code owner}, or empty when
     * the element belongs to no role and should keep its per-class file.
     */
    public Optional<String> roleFor(TaggedElement owner) {
        String key = owner.kind() + "::" + owner.qualifiedName();
        return roleMemo.computeIfAbsent(key, unused -> matchRole(owner));
    }

    /**
     * The uncached match, one role at a time in config order: the role's exact names, then its
     * compiled globs. Config order decides first, so an earlier role's glob beats a later role's
     * exact name.
     *
     * <p>Cost is roles times globs regex matches per element, and the same element is asked several
     * times per build — once by {@code GranularRulesWriter.plan}, which itself runs two to three
     * times, and once per indexed aggregate through {@code GranularIndexSection.scopedPath}.
     * Memoised in {@link #roleMemo} because the answer depends only on this config and the element,
     * both of which are fixed for the life of this object.
     */
    private Optional<String> matchRole(TaggedElement owner) {
        String path = ownerPath(owner);
        String fqn = owner.qualifiedName();
        for (Role role : roles) {
            if (role.fqns().contains(fqn)) {
                return Optional.of(role.name());
            }
            for (Pattern p : role.globPatterns()) {
                if (p.matcher(path).matches()) {
                    return Optional.of(role.name());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Every glob declared for {@code roleName}, in config order, de-duplicated.
     *
     * <p>All of them, not the first line's. A name may appear on more than one line, and routing
     * has always treated that as one role: {@code roleFor} answers with the name, so every class
     * either line matches lands in the one rule file. Answering here with the first line alone gave
     * that file frontmatter naming only the first line's glob, so it never loaded when the agent
     * opened a class the second line had routed into it. Nothing failed and the guardrails were in
     * the file; the file was simply not read, which is the one thing it exists for.
     */
    public List<String> globsFor(String roleName) {
        Set<String> globs = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role.name().equals(roleName)) {
                globs.addAll(role.globs());
            }
        }
        return List.copyOf(globs);
    }

    /**
     * The rule-file stem that {@code GranularRulesWriter} writes for {@code owner}: the sanitized
     * role name when the element matches a role, otherwise the per-class granular qName. The single
     * source of truth so a scoped-rules index pointer can never drift from the file it references
     * (see {@code GranularIndexSection}).
     */
    public String granularStemFor(TaggedElement owner) {
        return roleFor(owner).map(RoleConfig::sanitize).orElseGet(owner::granularQName);
    }

    /** Role names are user-authored; keep filenames to filesystem-safe characters. */
    public static String sanitize(String roleName) {
        return roleName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    /** A token is a glob (not an FQN) if it contains any glob metacharacter or a path separator. */
    private static boolean isGlob(String token) {
        return token.indexOf('*') >= 0 || token.indexOf('?') >= 0
            || token.indexOf('/') >= 0 || token.indexOf('{') >= 0;
    }

    /** Splits the right-hand side on commas, but NOT commas inside a {@code {a,b}} brace group. */
    private static List<String> splitMatchers(String rhs) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < rhs.length(); i++) {
            char c = rhs.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Reconstructs a path from the element's FQN: {@code a.b.Foo} → {@code a/b/Foo.java}; a package → {@code a/b/pkg/}. */
    private static String ownerPath(TaggedElement owner) {
        String slashed = owner.qualifiedName().replace('.', '/');
        return owner.kind() == ElementTag.PACKAGE ? slashed + "/" : slashed + ".java";
    }

    /**
     * Converts a glob to an anchored regex matched against the {@code /}-joined reconstructed path
     * (so it is separator-independent, unlike {@code FileSystems.getPathMatcher}). Supports
     * {@code **} (any depth, including zero segments for {@code **}{@code /}), {@code *} (within a
     * segment), {@code ?}, and {@code {a,b}} alternation.
     */
    static Pattern globToRegex(String glob) {
        return Pattern.compile(new GlobRegex(glob).translate());
    }

    /** One glob's translation, a character at a time; the open brace groups are its only state. */
    private static final class GlobRegex {

        private final String glob;
        private final StringBuilder regex = new StringBuilder("^");
        private int braceDepth;

        GlobRegex(String glob) {
            this.glob = glob;
        }

        String translate() {
            int i = 0;
            while (i < glob.length()) {
                i = glob.charAt(i) == '*' ? star(i) : other(i);
            }
            return regex.append('$').toString();
        }

        /** The star run at {@code i}, returning the index after it. */
        private int star(int i) {
            if (!glob.startsWith("**", i)) {
                regex.append("[^/]*"); // '*' stays within one segment
                return i + 1;
            }
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?"); // '**/' is zero or more whole segments
                return i + 3;
            }
            regex.append(".*"); // a trailing or standalone '**' is any depth
            return i + 2;
        }

        /** Any character but a star, returning the index after it. */
        private int other(int i) {
            char c = glob.charAt(i);
            switch (c) {
                case '?' -> regex.append("[^/]");
                case '.' -> regex.append("\\.");
                case '{' -> openGroup();
                case '}' -> closeGroup();
                case ',' -> regex.append(braceDepth > 0 ? "|" : "\\,");
                case '\\', '(', ')', '[', ']', '^', '$', '+', '|' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
            return i + 1;
        }

        private void openGroup() {
            braceDepth++;
            regex.append("(?:");
        }

        /** Closes the innermost group; a brace with none open is a literal. */
        private void closeGroup() {
            if (braceDepth > 0) {
                braceDepth--;
                regex.append(')');
            } else {
                regex.append("\\}");
            }
        }
    }

    /** One role: name, its glob strings (for frontmatter), compiled glob patterns, and exact FQNs. */
    private record Role(String name, List<String> globs, List<Pattern> globPatterns, Set<String> fqns) {

        /**
         * The role a trimmed {@code line} declares, or {@code null} for a blank line, a comment,
         * a line without a name before its first {@code =}, or one whose matchers are all empty
         * or malformed.
         */
        static @Nullable Role parse(String line) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) {
                return null; // a trimmed line with '=' past column 0 has a non-blank name
            }
            Role role = new Role(line.substring(0, eq).trim(), new ArrayList<>(), new ArrayList<>(),
                new LinkedHashSet<>());
            for (String token : splitMatchers(line.substring(eq + 1))) {
                role.add(token.trim());
            }
            return role.globs.isEmpty() && role.fqns.isEmpty() ? null : role;
        }

        /** Adds one matcher: a glob when it has a glob character or a separator, else an exact name. */
        private void add(String token) {
            if (token.isEmpty()) {
                return;
            }
            if (!isGlob(token)) {
                fqns.add(token);
                return;
            }
            Pattern pattern = compiled(token);
            if (pattern != null) {
                globs.add(token);
                globPatterns.add(pattern);
            }
        }

        /**
         * {@code glob} compiled, or {@code null} when it does not compile (an unclosed brace group
         * is the usual one), which then matches nothing. Throwing here reached the top of the
         * generate phase and stopped every output of the build over one typo; the file's other
         * matchers are unaffected by it and keep routing.
         */
        private static @Nullable Pattern compiled(String glob) {
            try {
                return globToRegex(glob);
            } catch (java.util.regex.PatternSyntaxException malformed) {
                return null;
            }
        }
    }
}
