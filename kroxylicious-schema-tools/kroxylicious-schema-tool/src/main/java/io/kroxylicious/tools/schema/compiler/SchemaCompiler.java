/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.tools.schema.compiler;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;

import io.kroxylicious.tools.schema.model.SchemaObject;
import io.kroxylicious.tools.schema.model.VisitException;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public class SchemaCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaCompiler.class);

    private final List<Path> src;
    private final CodeGen codeGen;
    private final YAMLMapper mapper;
    private final Namer namer;
    private final @Nullable String header;
    private final Diagnostics diagnostics;

    public SchemaCompiler(List<Path> src,
                          @Nullable String header,
                          Map<String, String> existingClasses) {
        this.src = Objects.requireNonNull(src);
        if (header != null) {
            header = maybeWrapInComment(header);
        }
        this.header = header;

        this.diagnostics = new Diagnostics();
        this.mapper = new YAMLMapper();
        this.namer = new Namer(diagnostics);
        this.codeGen = new CodeGen(diagnostics, namer, existingClasses);
    }

    @NonNull
    private static String maybeWrapInComment(@NonNull String header) {
        // Is the header already a comment? Let's parse it as java and find out
        try {
            var cu = StaticJavaParser.parse(header);
            if (cu.getChildNodes().stream().anyMatch(node -> !(node instanceof Comment))) {
                // The header wasn't (just) a comment, and was valid java (!) => we should turn it into a comment
                throw new ParseProblemException(new RuntimeException());
            }
        }
        catch (ParseProblemException e) {
            // The header is not already a comment
            var nl = System.lineSeparator();
            header = "/*" + nl + header.lines()
                    .map(line -> " * " + line.replace("*/", "* /"))
                    .collect(Collectors.joining(nl)) + nl + " */" + nl;
        }
        return header;
    }

    public List<Input> parse() {

        return src.stream().flatMap(srcPath -> {
            LOGGER.debug("Parsing {}", srcPath.toAbsolutePath());
            try (Stream<Path> walkPaths = Files.walk(srcPath)) {
                return walkPaths.flatMap(file -> {
                    String string = file.getFileName().toString();
                    if (Files.isRegularFile(file)
                            && (string.endsWith(".yaml")
                                    || string.endsWith(".yml")
                                    || string.endsWith(".json"))) {
                        return parseSchema(srcPath, file);
                    }
                    return Stream.empty();
                }).toList().stream(); // Need to materialise this list else the try(walkPaths) will close Stream before anything pulled through the stream
            }
            catch (IOException e) {
                throw new UncheckedIOException("Unable to walk source directory " + srcPath, e);
            }
        }).toList();
    }

    private Stream<Input> parseSchema(Path srcPath, Path schemaFile) {
        try {
            var relPath = srcPath.relativize(schemaFile);
            LOGGER.debug("Parsing {}", schemaFile);
            var tree = mapper.readTree(schemaFile.toFile());

            JsonNode $schema = tree.path("$schema");
            if ($schema.isMissingNode()) {
                diagnostics.reportWarning("Ignoring non-schema file: {}", schemaFile);
                return Stream.empty();
            }
            if (!"http://json-schema.org/draft-04/schema#".equals($schema.asText("http://json-schema.org/draft-04/schema#"))) {
                diagnostics.reportWarning("Ignoring non-schema file: {}", schemaFile);
                return Stream.empty();
            }
            var rootSchema = mapper.convertValue(tree, SchemaObject.class);

            // Build the map of absolute URI identifiers to schema
            rootSchema.visitSchemas(schemaFile.toUri(), namer);

            // We should now be able to resolve local $ref
            String rootClass = schemaFile.getFileName().toString().replaceAll("\\.yaml$", "");
            var refResolver = new RefResolver(diagnostics, namer, rootClass);
            rootSchema.visitSchemas(schemaFile.toUri(), refResolver);

            String pkg = StreamSupport.stream(relPath.getParent().spliterator(), false)
                    .map(Path::toString)
                    .collect(Collectors.joining("."));

            return Stream.of(new Input(schemaFile, pkg, rootSchema));
        }
        catch (IOException | IllegalArgumentException | VisitException e) {
            diagnostics.reportError("Unable to read source file {}: {}", schemaFile, e.getMessage());
            return Stream.empty();
        }
    }

    public Stream<CompilationUnit> gen(List<Input> inputs) {
        try {
            return inputs.stream()
                    .flatMap(input -> codeGen.genDecls(input).stream());
        }
        catch (VisitException e) {
            diagnostics.reportFatal("Error: {}", e.getMessage(), e);
            return Stream.empty();
        }
    }

    public int numFatals() {
        return diagnostics.getNumFatals();
    }

    public int numErrors() {
        return diagnostics.getNumErrors();
    }

    public int numWarnings() {
        return diagnostics.getNumWarnings();
    }

    public void write(Path dst, Stream<CompilationUnit> units) {

        units.forEach(compilationUnit -> {
            String pkg = packageName(compilationUnit);
            String dirname = pkg.replace(".", File.separator);
            Path parent = dst.resolve(dirname);
            String javaFileName = javaFileName(compilationUnit);
            Path javaFile = parent.resolve(javaFileName);

            try {
                Files.createDirectories(parent);
            }
            catch (IOException e) {
                diagnostics.reportFatal("Unable to create dst directory {}", parent, e);
            }

            try {
                if (header != null) {
                    Files.writeString(javaFile, header);
                    Files.writeString(javaFile, compilationUnit.toString(), StandardOpenOption.APPEND);
                }
                else {
                    Files.writeString(javaFile, compilationUnit.toString());
                }
            }
            catch (IOException e) {
                diagnostics.reportFatal("Unable to write output java file {}", javaFile, e);
            }
        });
    }

    @NonNull
    static String javaFileName(CompilationUnit compilationUnit) {
        return compilationUnit.getTypes().stream()
                .filter(t -> t.isPublic() && t.isTopLevelType())
                .findFirst()
                .map(NodeWithSimpleName::getNameAsString)
                .orElseThrow() + ".java";
    }

    private static String packageName(CompilationUnit compilationUnit) {
        return compilationUnit.getPackageDeclaration().orElseThrow().getNameAsString();
    }

    public static void main(String[] a) {
        List<Path> src = List.of(Path.of("kroxylicious-schema-tools/src/test/schema"));
        Path dst = Path.of("kroxylicious-schema-tools/target/generated-test-sources/foos");

        var compiler = new SchemaCompiler(src, null, Map.of());
        var trees = compiler.parse();
        var units = compiler.gen(trees);
        compiler.write(dst, units);
    }

}