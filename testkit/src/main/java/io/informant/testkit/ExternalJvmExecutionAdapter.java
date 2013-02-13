/**
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.testkit;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.MainEntryPoint;
import io.informant.core.util.ThreadSafe;
import io.informant.testkit.InformantContainer.ExecutionAdapter;
import io.informant.testkit.internal.ClassPath;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ExternalJvmExecutionAdapter implements ExecutionAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ExternalJvmExecutionAdapter.class);

    private final Process process;
    private final ExecutorService consolePipeExecutorService;
    private final SocketCommander socketCommander;
    private final Thread shutdownHook;

    ExternalJvmExecutionAdapter(final Map<String, String> properties) throws IOException {
        socketCommander = new SocketCommander();
        List<String> command = buildCommand(properties, socketCommander.getLocalPort());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService.submit(new Runnable() {
            public void run() {
                try {
                    ByteStreams.copy(process.getInputStream(), System.out);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        shutdownHook = new Thread() {
            @Override
            public void run() {
                try {
                    socketCommander.sendKillCommand();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        };
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public int getPort() throws IOException, InterruptedException {
        return (Integer) socketCommander.sendCommand(SocketCommandProcessor.GET_PORT_COMMAND);
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws IOException, InterruptedException {
        socketCommander.sendCommand(ImmutableList.of(SocketCommandProcessor.EXECUTE_APP_COMMAND,
                appUnderTestClass.getName(), threadName));
    }

    public void close() throws IOException, InterruptedException {
        Object response = socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN_COMMAND);
        if (response.equals(SocketCommandProcessor.EXCEPTION_RESPONSE)) {
            throw new IllegalStateException("Exception occurred inside external JVM");
        }
        socketCommander.close();
        process.waitFor();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }

    public void kill() throws IOException, InterruptedException {
        socketCommander.sendKillCommand();
        socketCommander.close();
        process.waitFor();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }

    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(args[0]);
            Socket socket = new Socket((String) null, port);
            ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
            new Thread(new SocketCommandProcessor(objectIn, objectOut)).start();
            new Thread(new SocketHeartbeat(objectOut)).start();
        } catch (Throwable t) {
            // log error and exit gracefully
            logger.error(t.getMessage(), t);
        }
    }

    private static List<String> buildCommand(Map<String, String> properties, int port)
            throws IOException {
        List<String> command = Lists.newArrayList();
        String javaExecutable = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        String classpath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classpath);
        command.addAll(getJavaAgentsFromCurrentJvm());
        File javaagentJarFile = ClassPath.getInformantCoreJarFile();
        if (javaagentJarFile == null) {
            javaagentJarFile = ClassPath.getDelegatingJavaagentJarFile();
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + MainEntryPoint.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        for (Entry<String, String> agentProperty : properties.entrySet()) {
            command.add("-Dinformant." + agentProperty.getKey() + "=" + agentProperty.getValue());
        }
        command.add(ExternalJvmExecutionAdapter.class.getName());
        command.add(Integer.toString(port));
        return command;
    }

    private static List<String> getJavaAgentsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> javaAgents = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:")) {
                // pass on the jacoco agent in particular
                javaAgents.add(argument);
            }
        }
        return javaAgents;
    }
}