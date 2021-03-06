package org.unicode.icu.tool.cldrtoicu;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.function.Function;
import java.util.logging.Formatter;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import org.unicode.cldr.api.CldrDataSupplier;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;

/**
 * Utility to allow any "mapper" class to trivially support a main method and useful
 * logging behaviour to help avoid the need for ad-hoc logging via {@code System.out}.
 *
 * <p>In most cases a mapping class can just have a {@code main} method like:
 * <pre>{@code
 *   // Arguments: <output-dir> [<log-level>]
 *   public static void main(String[] args) throws IOException {
 *       DebugWriter.writeForDebugging(args, MapperClass::process);
 *   }
 * }</pre>
 *
 * <p>Note however that running this still requires that the {@code CLDR_DIR} system
 * property is set.
 */
public final class DebugWriter {
    private static final String PACKAGE_ROOT = "org.unicode.icu.tool.cldrtoicu";
    private static final String PACKAGE_PREFIX = PACKAGE_ROOT + ".";

    /**
     * Writes the IcuData generated by the given function using the default {@code CLDR_DIR}
     * system property.
     *
     * <p>This is a helper method to make it easy for each mapper to have its own main
     * method for debugging, and it should not be used directly by {@code LdmlConverter}.
     */
    public static void writeForDebugging(String[] args, Function<CldrDataSupplier, IcuData> fn)
            throws IOException {
        writeMultipleForDebugging(args, src -> ImmutableList.of(fn.apply(src)));
    }

    /**
     * Writes the IcuData generated by the given function using the default {@code CLDR_DIR}
     * system property.
     *
     * <p>This is a helper method to make it easy for each mapper to have its own main
     * method for debugging, and it should not be used directly by {@code LdmlConverter}.
     */
    public static void writeMultipleForDebugging(
            String[] args, Function<CldrDataSupplier, Collection<IcuData>> fn)
            throws IOException {
        String cldrPath = System.getProperty("CLDR_DIR", System.getenv("CLDR_DIR"));
        checkState(cldrPath != null,
                "required 'CLDR_DIR' system property or environment variable not set");
        checkArgument(args.length >= 1, "expected output directory");
        Path outDir = Paths.get(args[0]);
        String logLevel = (args.length == 2) ? args[1] : "OFF";

        String loggerConfig = Joiner.on("\n").join(
                "handlers = java.util.logging.ConsoleHandler",
                "java.util.logging.ConsoleHandler.level     = ALL",
                "java.util.logging.ConsoleHandler.encoding  = UTF-8",
                "java.util.logging.ConsoleHandler.formatter = " + LogFormatter.class.getName(),
                "",
                PACKAGE_ROOT + ".level  = " + logLevel);
        LogManager.getLogManager()
                .readConfiguration(new ByteArrayInputStream(loggerConfig.getBytes(UTF_8)));

        Files.createDirectories(outDir);
        CldrDataSupplier src = CldrDataSupplier.forCldrFilesIn(Paths.get(cldrPath));
        ImmutableList<String> header = readLinesFromResource("/ldml2icu_header.txt");
        for (IcuData icuData : fn.apply(src)) {
            IcuTextWriter.writeToFile(icuData, outDir, header, true);
        }
    }

    private static ImmutableList<String> readLinesFromResource(String name) {
        try (InputStream in = DebugWriter.class.getResourceAsStream(name)) {
            return ImmutableList.copyOf(CharStreams.readLines(new InputStreamReader(in, UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException("cannot read resource: " + name, e);
        }
    }

    // Format is "<localClass>#<plainMethod>: <message>" since this is a fairly
    // small code base and keeping logs concise is helpful.
    // This is only public because it has to be reflectively instantiated.
    public static final class LogFormatter extends Formatter {
        private static final CharMatcher SEPARATORS = CharMatcher.anyOf("$#");

        @Override
        public String format(LogRecord logRecord) {
            String message = String.format("%s#%s: %s\n",
                    localClassName(logRecord.getSourceClassName()),
                    plainMethodName(logRecord.getSourceMethodName()),
                    logRecord.getMessage());
            if (logRecord.getThrown() != null) {
                message += logRecord.getThrown() + "\n";
            }
            return message;
        }

        // Since everything is in the same base package, elide that (if present).
        private String localClassName(String className) {
            return className.startsWith(PACKAGE_PREFIX)
                    ? className.substring(className.lastIndexOf(".") + 1)
                    : className;
        }

        // Trim method names to remove things like lambda prefixes and anonymous
        // class suffixes (these add noise to every log and aren't that useful).
        private String plainMethodName(String methodName) {
            if (methodName.startsWith("lambda$")) {
                methodName = methodName.substring("lambda$".length());
            }
            if (SEPARATORS.matchesAnyOf(methodName)) {
                methodName = methodName.substring(0, SEPARATORS.indexIn(methodName));
            }
            return methodName;
        }
    }
}
