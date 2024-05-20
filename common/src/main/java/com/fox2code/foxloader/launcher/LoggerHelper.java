package com.fox2code.foxloader.launcher;

import com.fox2code.foxloader.launcher.utils.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.util.Date;
import java.util.logging.*;

final class LoggerHelper {
    private static final String format = "[%1$tT] [%2$-7s] %3$s";
    private static final Level STDOUT = new Level("STDOUT", 800, Level.INFO.getResourceBundleName()) {};
    private static final Level STDERR = new Level("STDERR", 1000, Level.INFO.getResourceBundleName()) {};
    static boolean devEnvironment = Boolean.getBoolean("foxloader.dev-mode") || // Assume true if dev-mode.
            FoxLauncher.foxLoaderFile.getAbsolutePath().replace('\\', '/').endsWith( // Also check for IDE launch.
                    "/common/build/libs/common-" + BuildConfig.FOXLOADER_VERSION + ".jar");
    private static final boolean consoleSupportColor = devEnvironment ||
            Boolean.getBoolean("foxloader.console-support-color") ||
            (Platform.getPlatform() != Platform.WINDOWS && System.console() != null);
    private static final boolean disableLoggerHelper =
            Boolean.getBoolean("foxloader.disable-logger-helper");
    private static final FoxLoaderLogFormatter simpleFormatter = new FoxLoaderLogFormatter();
    private static SystemOutConsoleHandler systemOutConsoleHandler;
    private static DirectFileHandler directFileHandler;

    static boolean install(File logFile) {
        if (disableLoggerHelper) {
            System.out.println("The LoggerHelper has been disabled, things aren't gonna look pretty");
            return true;
        }
        if (System.out.getClass() != PrintStream.class) {
            System.out.println("System out has been modified, skipping install.");
            try (PrintStream printStream = new PrintStream(Files.newOutputStream(logFile.toPath()))){
                new CantInstallLoggerHelperException( // Help with debugging
                        "Failed to install LoggerHelper cause the current output class is " +
                                System.out.getClass().getName()).printStackTrace(printStream);
            } catch (IOException ignored) {}
            // If System.out already has been replaced just ignore the replacement.
            return false;
        }
        boolean installed = false;
        final SystemOutConsoleHandler systemOutConsoleHandler = new SystemOutConsoleHandler();
        final Logger rootLogger = LogManager.getLogManager().getLogger("");
        final DirectFileHandler directFileHandler;
        try {
            directFileHandler = new DirectFileHandler(logFile);
            rootLogger.addHandler(directFileHandler);
        } catch (Exception ignored) {
            return false;
        }
        LoggerHelper.systemOutConsoleHandler = systemOutConsoleHandler;
        LoggerHelper.directFileHandler = directFileHandler;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            directFileHandler.flush();
            systemOutConsoleHandler.flush();
        }, "LoggerHelper exit flush shutdown hook"));
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            if (handler instanceof ConsoleHandler) {
                installed = true;
                rootLogger.removeHandler(handler);
                rootLogger.addHandler(systemOutConsoleHandler);
            } else {
                handler.setFormatter(LoggerHelper.simpleFormatter);
            }
        }
        if (installed) {
            final PrintStream out = System.out;
            out.flush(); // <- Make sure buffer is flushed
            System.setOut(new FoxLoaderLogPrintStream(out, rootLogger, STDOUT, false));
            System.setErr(new FoxLoaderLogPrintStream(out, rootLogger, STDERR, true));
        }
        return installed;
    }

    static void installOn(Logger logger) {
        Handler[] handlers = logger.getHandlers();
        boolean hasDirectFileHandler = false;
        for (Handler handler : handlers) {
            if (handler instanceof ConsoleHandler) {
                if (handler != systemOutConsoleHandler) {
                    logger.removeHandler(handler);
                    logger.addHandler(systemOutConsoleHandler);
                }
            } else if (handler == directFileHandler) {
                hasDirectFileHandler = true;
            }
        }
        if (!logger.getUseParentHandlers() && !hasDirectFileHandler) {
            logger.addHandler(directFileHandler);
        }
    }

    @SuppressWarnings({"UnnecessaryCallToStringValueOf", "StringOperationCanBeSimplified"})
    private static final class FoxLoaderLogPrintStream extends PrintStream {
        private final Logger rootLogger;
        private final Level level;
        private final boolean doSkips;
        private boolean skip;

        public FoxLoaderLogPrintStream(@NotNull OutputStream out, Logger rootLogger, Level level, boolean doSkips) {
            super(out, true);
            this.rootLogger = rootLogger;
            this.level = level;
            this.doSkips = doSkips;
        }

        @Override
        public void println() {
            this.println("");
        }

        @Override
        public void print(int i) {
            this.print(String.valueOf(i));
        }

        @Override
        public void println(@Nullable Object x) {
            this.println(String.valueOf(x));
        }

        @Override
        public void println(@Nullable String line) {
            if (line == null) line = "null";
            if (doSkips && line.startsWith( // Normal on linux!
                    "java.io.IOException: Cannot run program \"sensible-browser\"")) {
                skip = true;
            } else if (skip) {
                if (!line.startsWith("\t") && !line.startsWith("    ")
                        && !line.startsWith("Caused by:")) {
                    skip = false;
                }
            }
            if (!skip) {
                rootLogger.log(level, line);
            }
        }
    }

    private static class FoxLoaderLogFormatter extends SimpleFormatter {
        private final Date date = new Date();

        @Override
        public synchronized String format(LogRecord lr) {
            String message = lr.getMessage();
            String sessionToken = FoxLauncher.initialSessionId;
            if (sessionToken != null && sessionToken.length() > 4) {
                message = message.replace(sessionToken, "<session token>");
            }
            Throwable throwable = lr.getThrown();
            if (throwable != null) {
                message += "\n" + StackTraceStringifier.stringifyStackTrace(throwable);
            }
            date.setTime(lr.getMillis());
            return String.format(format, date,
                    lr.getLevel().getLocalizedName(),
                    message
            ).trim() + '\n';
        }
    }

    private static class FoxLoaderConsoleLogFormatter extends FoxLoaderLogFormatter {
        public static final String RESET = "\033[0m";
        public static final String RED = "\033[0;31m";
        public static final String GREEN = "\033[0;32m";
        public static final String YELLOW = "\033[0;33m";
        public static final String BLUE = "\033[0;34m";

        @Override
        public synchronized String format(LogRecord lr) {
            String text = super.format(lr);
            if (!consoleSupportColor) return text;
            String color;
            switch (lr.getLevel().intValue()) {
                default:
                    return text;
                case 500: // FINE
                    color = GREEN;
                    break;
                case 700: // CONFIG
                    color = BLUE;
                    break;
                case 900: // WARNING
                    color = YELLOW;
                    break;
                case 1000: // SEVERE
                    color = RED;
                    break;
            }
            return color + text + RESET;
        }
    }

    private static class DirectFileHandler extends StreamHandler {
        DirectFileHandler(File file) throws IOException {
            setOutputStream(Files.newOutputStream(file.toPath()));
            setLevel(Level.ALL);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }
    }

    private static class SystemOutConsoleHandler extends ConsoleHandler {
        SystemOutConsoleHandler() {
            setFormatter(new FoxLoaderConsoleLogFormatter());
            setLevel(Level.ALL);
        }

        @Override
        protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
        	super.setOutputStream(System.out);
        }
    }

    private static class CantInstallLoggerHelperException extends Exception {
        CantInstallLoggerHelperException(String message) {
            super(message);
        }
    }
}
