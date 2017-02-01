package com.github.eirslett.maven.plugins.frontend.lib;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.ShutdownHookProcessDestroyer;
import org.slf4j.Logger;

final class ProcessExecutionException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProcessExecutionException(String message) {
        super(message);
    }
    public ProcessExecutionException(Throwable cause) {
        super(cause);
    }
}

final class ProcessExecutor {
    private File lockFile = new File(System.getProperty("java.io.tmpdir") + File.separator + ProcessExecutor.class.getCanonicalName() + ".lock");
    private RandomAccessFile randomAccessFile;
    private FileLock fileLock = null;
    private final Map<String, String> environment;
    private CommandLine commandLine;
    private final Executor executor;

    public ProcessExecutor(File workingDirectory, List<String> paths, List<String> command, Platform platform, Map<String, String> additionalEnvironment){
        this(workingDirectory, paths, command, platform, additionalEnvironment, 0);
    }

    public ProcessExecutor(File workingDirectory, List<String> paths, List<String> command, Platform platform, Map<String, String> additionalEnvironment, long timeoutInSeconds) {
        this.environment = createEnvironment(paths, platform, additionalEnvironment);
        this.commandLine = createCommandLine(command);
        this.executor = createExecutor(workingDirectory, timeoutInSeconds);
    }

    public String executeAndGetResult(final Logger logger) throws ProcessExecutionException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitValue = execute(logger, stdout, stderr);
        if (exitValue == 0) {
            return stdout.toString().trim();
        } else {
            throw new ProcessExecutionException(stdout + " " + stderr);
        }
    }

    public int executeAndRedirectOutput(final Logger logger) throws ProcessExecutionException {
        OutputStream stdout = new LoggerOutputStream(logger, 0);
        OutputStream stderr = new LoggerOutputStream(logger, 1);

        return execute(logger, stdout, stderr);
    }

    private int execute(final Logger logger, final OutputStream stdout, final OutputStream stderr)
            throws ProcessExecutionException {
        logger.debug("Executing command line {}", commandLine);
        try {
            acquireLock(logger);
            try {
                ExecuteStreamHandler streamHandler = new PumpStreamHandler(stdout, stderr);
                executor.setStreamHandler(streamHandler);

                int exitValue = executor.execute(commandLine, environment);
                logger.debug("Exit value {}", exitValue);

                return exitValue;
            } catch (ExecuteException e) {
                if (executor.getWatchdog() != null && executor.getWatchdog()
                        .killedProcess()) {
                    throw new ProcessExecutionException("Process killed after timeout");
                }
                throw new ProcessExecutionException(e);
            } catch (IOException e) {
                throw new ProcessExecutionException(e);
            }
        } finally {
            releaseLock(logger);
        }
    }

    private void acquireLock(final Logger logger) {
        long timeout = System.currentTimeMillis() + 600000;
        if (!lockFile.exists()) {
            try {
                lockFile.createNewFile();
            } catch (IOException e) {
                logger.warn("Unable to create lock file.", e);
            }
        }
        try {
            randomAccessFile = new RandomAccessFile(lockFile, "rw");
        } catch (FileNotFoundException e) {
            logger.warn("Unable to create random access file for lock.", e);
        }
        if (randomAccessFile != null) {
            FileChannel fileChannel = randomAccessFile.getChannel();
            while (System.currentTimeMillis() < timeout) {
                try {
                    fileLock = fileChannel.tryLock();
                } catch (IOException e) {
                    logger.warn("Exception while attempting lock.", e);
                } catch (OverlappingFileLockException e) {
                    //ignore
                }
                if (fileLock != null) {
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while acquiring lock.", e);
                }
            }
        }
    }

    private void releaseLock(final Logger logger) {
        if (fileLock != null) {
            try {
                fileLock.release();
                fileLock = null;
            } catch (IOException e) {
                logger.warn("Exception while releasing lock.", e);
            }
        }
        if (randomAccessFile != null) {
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                logger.warn("Exception while closing random access file.", e);
            }
        }
    }

    private CommandLine createCommandLine(List<String> command) {
        CommandLine commmandLine = new CommandLine(command.get(0));

        for (int i = 1;i < command.size();i++) {
            String argument = command.get(i);
            commmandLine.addArgument(argument, false);
        }

        return commmandLine;
    }

    private Map<String, String> createEnvironment(List<String> paths, Platform platform, Map<String, String> additionalEnvironment) {
        final Map<String, String> environment = new HashMap<>(System.getenv());
        String pathVarName = "PATH";
        String pathVarValue = environment.get(pathVarName);
        if (platform.isWindows()) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                if ("PATH".equalsIgnoreCase(entry.getKey())) {
                    pathVarName = entry.getKey();
                    pathVarValue = entry.getValue();
                }
            }
        }

        StringBuilder pathBuilder = new StringBuilder();
        if (pathVarValue != null) {
            pathBuilder.append(pathVarValue).append(File.pathSeparator);
        }
        for (String path : paths) {
            pathBuilder.insert(0, File.pathSeparator).insert(0, path);
        }
        environment.put(pathVarName, pathBuilder.toString());

        if (additionalEnvironment != null) {
            environment.putAll(additionalEnvironment);
        }

        return environment;
    }

    private Executor createExecutor(File workingDirectory, long timeoutInSeconds) {
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(workingDirectory);
        executor.setProcessDestroyer(new ShutdownHookProcessDestroyer());   // Fixes #41

        if (timeoutInSeconds > 0) {
            executor.setWatchdog(new ExecuteWatchdog(timeoutInSeconds * 1000));
        }

        return executor;
    }

    private static class LoggerOutputStream extends LogOutputStream {
        private final Logger logger;

        LoggerOutputStream(Logger logger, int logLevel) {
            super(logLevel);
            this.logger = logger;
        }

        @Override
        public final void flush() {
            // buffer processing on close() only
        }

        @Override
        protected void processLine(final String line, final int logLevel) {
            if (logLevel == 0) {
                logger.info(line);
            } else {
                if (line.startsWith("npm WARN ")) {
                    logger.warn(line);
                } else {
                    logger.error(line);
                }
            }
        }
    }
}
