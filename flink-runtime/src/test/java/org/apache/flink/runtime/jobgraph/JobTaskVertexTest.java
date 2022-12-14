/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobgraph;

import org.apache.flink.api.common.io.FinalizeOnMaster;
import org.apache.flink.api.common.io.GenericInputFormat;
import org.apache.flink.api.common.io.InitializeOnMaster;
import org.apache.flink.api.common.io.OutputFormat;
import org.apache.flink.api.java.io.DiscardingOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.GenericInputSplit;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.runtime.executiongraph.SimpleInitializeOnMasterContext;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.operators.util.TaskConfig;
import org.apache.flink.testutils.junit.SharedObjects;
import org.apache.flink.testutils.junit.SharedReference;
import org.apache.flink.util.InstantiationUtil;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("serial")
public class JobTaskVertexTest {

    @Rule public final SharedObjects sharedObjects = SharedObjects.create();

    @Test
    public void testConnectDirectly() {
        JobVertex source = new JobVertex("source");
        JobVertex target = new JobVertex("target");
        target.connectNewDataSetAsInput(
                source, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

        assertTrue(source.isInputVertex());
        assertFalse(source.isOutputVertex());
        assertFalse(target.isInputVertex());
        assertTrue(target.isOutputVertex());

        assertEquals(1, source.getNumberOfProducedIntermediateDataSets());
        assertEquals(1, target.getNumberOfInputs());

        assertEquals(target.getInputs().get(0).getSource(), source.getProducedDataSets().get(0));

        assertEquals(target, source.getProducedDataSets().get(0).getConsumer().getTarget());
    }

    @Test
    public void testOutputFormat() {
        try {
            final InputOutputFormatVertex vertex = new InputOutputFormatVertex("Name");

            OperatorID operatorID = new OperatorID();
            Configuration parameters = new Configuration();
            parameters.setString("test_key", "test_value");
            new InputOutputFormatContainer(Thread.currentThread().getContextClassLoader())
                    .addOutputFormat(operatorID, new TestingOutputFormat(parameters))
                    .addParameters(operatorID, parameters)
                    .write(new TaskConfig(vertex.getConfiguration()));

            final ClassLoader cl = new TestClassLoader();

            try {
                vertex.initializeOnMaster(
                        new SimpleInitializeOnMasterContext(cl, vertex.getParallelism()));
                fail("Did not throw expected exception.");
            } catch (TestException e) {
                // all good
            }

            InputOutputFormatVertex copy = InstantiationUtil.clone(vertex);
            ClassLoader ctxCl = Thread.currentThread().getContextClassLoader();
            try {
                copy.initializeOnMaster(
                        new SimpleInitializeOnMasterContext(cl, copy.getParallelism()));
                fail("Did not throw expected exception.");
            } catch (TestException e) {
                // all good
            }
            assertEquals(
                    "Previous classloader was not restored.",
                    ctxCl,
                    Thread.currentThread().getContextClassLoader());

            try {
                copy.finalizeOnMaster(
                        new SimpleInitializeOnMasterContext(cl, copy.getParallelism()));
                fail("Did not throw expected exception.");
            } catch (TestException e) {
                // all good
            }
            assertEquals(
                    "Previous classloader was not restored.",
                    ctxCl,
                    Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testInputFormat() {
        try {
            final InputOutputFormatVertex vertex = new InputOutputFormatVertex("Name");

            OperatorID operatorID = new OperatorID();
            Configuration parameters = new Configuration();
            parameters.setString("test_key", "test_value");
            new InputOutputFormatContainer(Thread.currentThread().getContextClassLoader())
                    .addInputFormat(operatorID, new TestInputFormat(parameters))
                    .addParameters(operatorID, "test_key", "test_value")
                    .write(new TaskConfig(vertex.getConfiguration()));

            final ClassLoader cl = new TestClassLoader();

            vertex.initializeOnMaster(
                    new SimpleInitializeOnMasterContext(cl, vertex.getParallelism()));
            InputSplit[] splits = vertex.getInputSplitSource().createInputSplits(77);

            assertNotNull(splits);
            assertEquals(1, splits.length);
            assertEquals(TestSplit.class, splits[0].getClass());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testOutputFormatUsesCorrectParallelism() throws Exception {
        final InputOutputFormatVertex vertex = new InputOutputFormatVertex("Name");
        int initialParallelism = 1;
        vertex.setParallelism(initialParallelism);

        OperatorID operatorID = new OperatorID();
        // just a mutable container for integer
        SharedReference<AtomicInteger> globalParallelism = sharedObjects.add(new AtomicInteger());
        new InputOutputFormatContainer(Thread.currentThread().getContextClassLoader())
                .addOutputFormat(operatorID, new TestInitializeOutputFormat(globalParallelism))
                .write(new TaskConfig(vertex.getConfiguration()));

        int executionParallelism = initialParallelism + 3;
        try (final TestClassLoader cl = new TestClassLoader()) {
            vertex.initializeOnMaster(
                    new SimpleInitializeOnMasterContext(cl, executionParallelism));
            assertThat(globalParallelism.get().get()).isEqualTo(executionParallelism);
        }
    }

    // --------------------------------------------------------------------------------------------

    private static final class TestInitializeOutputFormat
            implements OutputFormat<Object>, InitializeOnMaster {

        private final SharedReference<AtomicInteger> globalParallelism;

        private TestInitializeOutputFormat(SharedReference<AtomicInteger> globalParallelism) {
            this.globalParallelism = globalParallelism;
        }

        @Override
        public void configure(Configuration parameters) {}

        @Override
        public void open(int taskNumber, int numTasks) throws IOException {}

        @Override
        public void writeRecord(Object record) throws IOException {}

        @Override
        public void close() throws IOException {}

        @Override
        public void initializeGlobal(int parallelism) throws IOException {
            globalParallelism.get().set(parallelism);
        }
    }

    private static final class TestException extends IOException {}

    private static final class TestSplit extends GenericInputSplit {

        public TestSplit(int partitionNumber, int totalNumberOfPartitions) {
            super(partitionNumber, totalNumberOfPartitions);
        }
    }

    private static final class TestInputFormat extends GenericInputFormat<Object> {

        private boolean isConfigured = false;

        private final Configuration expectedParameters;

        public TestInputFormat(Configuration expectedParameters) {
            this.expectedParameters = expectedParameters;
        }

        @Override
        public boolean reachedEnd() {
            return false;
        }

        @Override
        public Object nextRecord(Object reuse) {
            return null;
        }

        @Override
        public GenericInputSplit[] createInputSplits(int numSplits) {
            if (!isConfigured) {
                throw new IllegalStateException(
                        "InputFormat was not configured before createInputSplits was called.");
            }
            return new GenericInputSplit[] {new TestSplit(0, 1)};
        }

        @Override
        public void configure(Configuration parameters) {
            if (isConfigured) {
                throw new IllegalStateException("InputFormat is already configured.");
            }
            if (!(Thread.currentThread().getContextClassLoader() instanceof TestClassLoader)) {
                throw new IllegalStateException("Context ClassLoader was not correctly switched.");
            }
            for (String key : expectedParameters.keySet()) {
                assertEquals(
                        expectedParameters.getString(key, null), parameters.getString(key, null));
            }
            isConfigured = true;
        }
    }

    private static final class TestingOutputFormat extends DiscardingOutputFormat<Object>
            implements InitializeOnMaster, FinalizeOnMaster {

        private boolean isConfigured = false;

        private final Configuration expectedParameters;

        public TestingOutputFormat(Configuration expectedParameters) {
            this.expectedParameters = expectedParameters;
        }

        @Override
        public void initializeGlobal(int parallelism) throws IOException {
            if (!isConfigured) {
                throw new IllegalStateException(
                        "OutputFormat was not configured before initializeGlobal was called.");
            }
            if (!(Thread.currentThread().getContextClassLoader() instanceof TestClassLoader)) {
                throw new IllegalStateException("Context ClassLoader was not correctly switched.");
            }
            // notify we have been here.
            throw new TestException();
        }

        @Override
        public void finalizeGlobal(int parallelism) throws IOException {
            if (!isConfigured) {
                throw new IllegalStateException(
                        "OutputFormat was not configured before finalizeGlobal was called.");
            }
            if (!(Thread.currentThread().getContextClassLoader() instanceof TestClassLoader)) {
                throw new IllegalStateException("Context ClassLoader was not correctly switched.");
            }
            // notify we have been here.
            throw new TestException();
        }

        @Override
        public void configure(Configuration parameters) {
            if (isConfigured) {
                throw new IllegalStateException("OutputFormat is already configured.");
            }
            if (!(Thread.currentThread().getContextClassLoader() instanceof TestClassLoader)) {
                throw new IllegalStateException("Context ClassLoader was not correctly switched.");
            }
            for (String key : expectedParameters.keySet()) {
                assertEquals(
                        expectedParameters.getString(key, null), parameters.getString(key, null));
            }
            isConfigured = true;
        }
    }

    private static class TestClassLoader extends URLClassLoader {
        public TestClassLoader() {
            super(new URL[0], Thread.currentThread().getContextClassLoader());
        }
    }
}
