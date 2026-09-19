package se.deversity.vibetags.ksp.internal;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.symbol.KSFile;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The processor's one use of a {@link Filer}: writing dependency manifests into the class output,
 * which under KSP is {@link CodeGenerator}'s resource output, packaged into the jar the same way.
 *
 * <p>Reading from the classpath is not available: KSP gives a processor no view of the
 * dependency classpath's resources. {@link #getResource} reports every lookup as not found, which
 * the transitive reader already treats as "no manifest here"; a KSP build reads inherited
 * guardrails through {@code -Avibetags.manifest.dir}, as a kapt build already has to.
 */
final class KspFiler implements Filer {

    private final CodeGenerator generator;
    private final List<KSFile> sources;

    KspFiler(CodeGenerator generator, List<KSFile> sources) {
        this.generator = generator;
        this.sources = List.copyOf(sources);
    }

    @Override
    public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
        throw new FilerException("VibeTags generates no source files");
    }

    @Override
    public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws IOException {
        throw new FilerException("VibeTags generates no class files");
    }

    @Override
    public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                     CharSequence relativeName, Element... originatingElements) throws IOException {
        if (location != StandardLocation.CLASS_OUTPUT) {
            throw new FilerException("unsupported location under KSP: " + location);
        }
        String pkg = moduleAndPkg.toString();
        String name = relativeName.toString();
        String path = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + name;
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? path : path.substring(0, path.length() - (name.length() - dot));
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        // Aggregating over every source: a manifest describes the whole module's package guardrails.
        Dependencies dependencies = new Dependencies(true, sources.toArray(new KSFile[0]));
        return new Resource(URI.create("ksp-resource:/" + path), generator, dependencies, stem, extension);
    }

    @Override
    public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                  CharSequence relativeName) throws IOException {
        throw new FileNotFoundException("classpath resources are not visible to a KSP processor: "
            + moduleAndPkg + "/" + relativeName);
    }

    /** A resource written through {@link CodeGenerator}, opened on first write. */
    private static final class Resource extends SimpleJavaFileObject {
        private final CodeGenerator generator;
        private final Dependencies dependencies;
        private final String stem;
        private final String extension;

        Resource(URI uri, CodeGenerator generator, Dependencies dependencies, String stem, String extension) {
            super(uri, Kind.OTHER);
            this.generator = generator;
            this.dependencies = dependencies;
            this.stem = stem;
            this.extension = extension;
        }

        @Override
        public OutputStream openOutputStream() {
            return generator.createNewFileByPath(dependencies, stem, extension);
        }

        @Override
        public Writer openWriter() {
            return new OutputStreamWriter(openOutputStream(), StandardCharsets.UTF_8);
        }

        @Override
        public @Nullable CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return null;
        }
    }
}
