package se.deversity.vibetags.processor.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

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
    public static RoleConfig load(Path root) {
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
        for (String raw : content.split("\r\n|\r|\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue; // no name or no '=' — skip silently
            }
            String name = line.substring(0, eq).trim();
            if (name.isEmpty()) {
                continue;
            }
            List<String> globs = new ArrayList<>();
            List<Pattern> globPatterns = new ArrayList<>();
            Set<String> fqns = new LinkedHashSet<>();
            for (String token : splitMatchers(line.substring(eq + 1))) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (isGlob(t)) {
                    globs.add(t);
                    globPatterns.add(globToRegex(t));
                } else {
                    fqns.add(t);
                }
            }
            if (!globs.isEmpty() || !fqns.isEmpty()) {
                parsed.add(new Role(name, globs, globPatterns, fqns));
            }
        }
        return new RoleConfig(parsed, BuildFingerprint.fingerprint(content));
    }

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
    public Optional<String> roleFor(Element owner) {
        String path = ownerPath(owner);
        String fqn = owner.toString();
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

    /** The glob patterns declared for {@code roleName} (used for the rule file's frontmatter). */
    public List<String> globsFor(String roleName) {
        for (Role role : roles) {
            if (role.name().equals(roleName)) {
                return role.globs();
            }
        }
        return List.of();
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
    private static String ownerPath(Element owner) {
        String slashed = owner.toString().replace('.', '/');
        return owner.getKind() == ElementKind.PACKAGE ? slashed + "/" : slashed + ".java";
    }

    /**
     * Converts a glob to an anchored regex matched against the {@code /}-joined reconstructed path
     * (so it is separator-independent, unlike {@code FileSystems.getPathMatcher}). Supports
     * {@code **} (any depth, including zero segments for {@code **}{@code /}), {@code *} (within a
     * segment), {@code ?}, and {@code {a,b}} alternation.
     */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int braceDepth = 0;
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    if (i + 2 < n && glob.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?"); // '**/' → zero or more path segments
                        i += 3;
                    } else {
                        sb.append(".*");       // trailing/standalone '**'
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");        // '*' → within one segment
                    i += 1;
                }
                continue;
            }
            switch (c) {
                case '?': sb.append("[^/]"); break;
                case '.': sb.append("\\."); break;
                case '{': braceDepth++; sb.append("(?:"); break;
                case '}': if (braceDepth > 0) { braceDepth--; sb.append(')'); } else { sb.append("\\}"); } break;
                case ',': sb.append(braceDepth > 0 ? "|" : "\\,"); break;
                case '\\': case '(': case ')': case '[': case ']':
                case '^': case '$': case '+': case '|':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
            i++;
        }
        return Pattern.compile(sb.append('$').toString());
    }

    /** One role: name, its glob strings (for frontmatter), compiled glob patterns, and exact FQNs. */
    private record Role(String name, List<String> globs, List<Pattern> globPatterns, Set<String> fqns) {}
}
