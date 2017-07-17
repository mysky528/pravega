/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.pravega.test.system;

import io.pravega.client.ClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.admin.impl.StreamManagerImpl;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ReinitializationRequiredException;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.ConnectionClosedException;
import io.pravega.client.stream.impl.Controller;
import io.pravega.client.stream.impl.JavaSerializer;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.test.system.framework.services.BookkeeperService;
import io.pravega.test.system.framework.services.PravegaControllerService;
import io.pravega.test.system.framework.services.PravegaSegmentStoreService;
import io.pravega.test.system.framework.services.Service;
import io.pravega.test.system.framework.services.ZookeeperService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Slf4j
abstract class AbstractFailoverTests {

    static final String STREAM = "testReadWriteAndAutoScaleStream";
    static final String STREAM_NAME = "testReadWriteAndScaleStream";
    static final int ADD_NUM_WRITERS = 6;
    static final int ZK_DEFAULT_SESSION_TIMEOUT = 30000;
    final String readerName = "reader";
    Service controllerInstance;
    Service segmentStoreInstance;
    URI controllerURIDirect = null;
    ExecutorService executorService;
    Controller controller;
    TestState testState;

    static class TestState {
        //read and write count variables
        final AtomicBoolean stopReadFlag = new AtomicBoolean(false);
        final AtomicBoolean stopWriteFlag = new AtomicBoolean(false);
        final AtomicLong eventReadCount = new AtomicLong(0); // used by readers to maintain a count of events.
        final AtomicLong eventWriteCount = new AtomicLong(0); // used by writers to maintain a count of events.
        final AtomicLong eventData = new AtomicLong(0); //data used by each of the writers.
        final ConcurrentLinkedQueue<Long> eventsReadFromPravega = new ConcurrentLinkedQueue<>();
        final AtomicReference<Throwable> getWriteException = new AtomicReference<>();
        final AtomicReference<Throwable> getReadException =  new AtomicReference<>();
        final AtomicInteger currentNumOfSegments = new AtomicInteger(0);
        final CompletableFuture<Void> writersComplete = new CompletableFuture<>();
        final CompletableFuture<Void> newWritersComplete = new CompletableFuture<>();
        final CompletableFuture<Void> readersComplete = new CompletableFuture<>();
    }

    void performFailoverTest() throws InterruptedException {

        log.info("Test with 3 controller, SSS instances running and without a failover scenario");
        long currentWriteCount1 = testState.eventWriteCount.get();
        long currentReadCount1 = testState.eventReadCount.get();
        log.info("Read count: {}, write count: {} without any failover", currentReadCount1, currentWriteCount1);

        //check reads and writes after some random time
        int sleepTime = new Random().nextInt(50000) + 3000;
        log.info("Sleeping for {} ", sleepTime);
        Thread.sleep(sleepTime);

        long currentWriteCount2 = testState.eventWriteCount.get();
        long currentReadCount2 = testState.eventReadCount.get();
        log.info("Read count: {}, write count: {} without any failover after sleep before scaling", currentReadCount2, currentWriteCount2);
        //ensure writes are happening
        assertTrue(currentWriteCount2 > currentWriteCount1);
        //ensure reads are happening
        assertTrue(currentReadCount2 > currentReadCount1);

        //Scale down SSS instances to 2
        segmentStoreInstance.scaleService(2, true);
        //zookeeper will take about 30 seconds to detect that the node has gone down
        Thread.sleep(ZK_DEFAULT_SESSION_TIMEOUT);
        log.info("Scaling down Segment Store instances from 3 to 2");

        currentWriteCount1 = testState.eventWriteCount.get();
        currentReadCount1 = testState.eventReadCount.get();
        log.info("Read count: {}, write count: {} after Segment Store  failover after sleep", currentReadCount1, currentWriteCount1);
        //ensure writes are happening
        assertTrue(currentWriteCount1 > currentWriteCount2);
        //ensure reads are happening
        assertTrue(currentReadCount1 > currentReadCount2);

        //Scale down controller instances to 2
        controllerInstance.scaleService(2, true);
        //zookeeper will take about 30 seconds to detect that the node has gone down
        Thread.sleep(ZK_DEFAULT_SESSION_TIMEOUT);
        log.info("Scaling down controller instances from 3 to 2");

        currentWriteCount2 = testState.eventWriteCount.get();
        currentReadCount2 = testState.eventReadCount.get();
        log.info("Read count: {}, write count: {} after controller failover after sleep", currentReadCount2, currentWriteCount2);
        //ensure writes are happening
        assertTrue(currentWriteCount2 > currentWriteCount1);
        //ensure reads are happening
        assertTrue(currentReadCount2 > currentReadCount1);

        //Scale down SSS, controller to 1 instance each.
        segmentStoreInstance.scaleService(1, true);
        controllerInstance.scaleService(1, true);
        //zookeeper will take about 30 seconds to detect that the node has gone down
        Thread.sleep(ZK_DEFAULT_SESSION_TIMEOUT);
        log.info("Scaling down  to 1 controller, 1 Segment Store  instance");

        currentWriteCount1 = testState.eventWriteCount.get();
        currentReadCount1 = testState.eventReadCount.get();
        log.info("Stop write flag status: {}, stop read flag status: {} ", testState.stopWriteFlag.get(), testState.stopReadFlag.get());
        log.info("Read count: {}, write count: {} with Segment Store  and controller failover after sleep", currentReadCount1, currentWriteCount1);
    }

    CompletableFuture<Void> startWriting(final EventStreamWriter<Long> writer) {
        return CompletableFuture.runAsync(() -> {
            while (!testState.stopWriteFlag.get()) {
                try {
                    long value = testState.eventData.incrementAndGet();
                    Thread.sleep(100);
                    log.debug("Event write count before write call {}", value);
                    writer.writeEvent(String.valueOf(value), value);
                    log.debug("Event write count before flush {}", value);
                    writer.flush();
                    testState.eventWriteCount.getAndIncrement();
                    log.debug("Writing event {}", value);
                } catch (InterruptedException e) {
                    log.error("Error in sleep: ", e);
                    testState.getWriteException.set(e);
                } catch (ConnectionClosedException e) {
                    log.warn("Test exception in writing events: ", e);
                    continue;
                } catch (Throwable e) {
                    log.error("Test exception in writing events: ", e);
                    testState.getWriteException.set(e);
                }
            }
            writer.close();
        }, executorService);
    }

    CompletableFuture<Void> startReading(final EventStreamReader<Long> reader) {
        return CompletableFuture.runAsync(() -> {
            log.info("Exit flag status: {}, Read count: {}, Write count: {}", testState.stopReadFlag.get(),
                    testState.eventReadCount.get(), testState.eventWriteCount.get());
            while (!(testState.stopReadFlag.get() && testState.eventReadCount.get() == testState.eventWriteCount.get())) {
                log.info("Entering read loop");
                // exit only if exitFlag is true  and read Count equals write count.
                try {
                    final Long longEvent = reader.readNextEvent(SECONDS.toMillis(60)).getEvent();
                    log.debug("Reading event {}", longEvent);
                    if (longEvent != null) {
                        //update if event read is not null.
                        testState.eventsReadFromPravega.add(longEvent);
                        testState.eventReadCount.incrementAndGet();
                        log.debug("Event read count {}", testState.eventReadCount.get());
                    } else {
                        log.debug("Read timeout");
                    }
                } catch (ConnectionClosedException e) {
                    log.warn("Test exception in reading events: ", e);
                    continue;
                } catch (ReinitializationRequiredException e) {
                    log.error("Test exception in reading events: ", e);
                    testState.getReadException.set(e);
                }
            }
            reader.close();
        }, executorService);
    }

    void cleanUp(String scope, String stream) throws InterruptedException, ExecutionException {
        CompletableFuture<Boolean> sealStreamStatus = controller.sealStream(scope, stream);
        log.info("Sealing stream {}", STREAM_NAME);
        assertTrue(sealStreamStatus.get());
        CompletableFuture<Boolean> deleteStreamStatus = controller.deleteStream(scope, stream);
        log.info("Deleting stream {}", STREAM_NAME);
        assertTrue(deleteStreamStatus.get());
        CompletableFuture<Boolean> deleteScopeStatus = controller.deleteScope(scope);
        log.info("Deleting scope {}", scope);
        assertTrue(deleteScopeStatus.get());
    }

    void createScopeAndStream(String scope, String stream, StreamConfiguration config, URI uri) {

        try (StreamManager streamManager = new StreamManagerImpl(uri)) {
            Boolean createScopeStatus = streamManager.createScope(scope);
            log.info("Creating scope with scope name {}", scope);
            log.debug("Create scope status {}", createScopeStatus);
            Boolean createStreamStatus = streamManager.createStream(scope, stream, config);
            log.debug("Create stream status {}", createStreamStatus);
        }
    }

    void createWriters(ClientFactory clientFactory, final int writers, String scope, String stream) {
        log.info("Client factory details {}", clientFactory.toString());
        log.info("Creating {} writers", writers);
        List<EventStreamWriter<Long>> writerList = new ArrayList<>(writers);
        List<CompletableFuture<Void>> writerFutureList = new ArrayList<>();
        log.info("Writers writing in the scope {}", scope);
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < writers; i++) {
                log.info("Starting writer{}", i);
                final EventStreamWriter<Long> tmpWriter = clientFactory.createEventWriter(stream,
                        new JavaSerializer<Long>(),
                        EventWriterConfig.builder().retryAttempts(10).build());
                writerList.add(tmpWriter);
                writerFutureList.add(startWriting(tmpWriter));
            }
        });
        FutureHelpers.completeAfter(() -> FutureHelpers.allOf(writerFutureList), testState.writersComplete);
    }

    void createReaders(ClientFactory clientFactory, String readerGroupName, String scope,
                                 ReaderGroupManager readerGroupManager, String stream, final int readers) {
        log.info("Creating Reader group: {}, with readergroup manager using scope: {}", readerGroupName, scope);
        readerGroupManager.createReaderGroup(readerGroupName, ReaderGroupConfig.builder().startingTime(0).build(),
                Collections.singleton(stream));
        log.info("Reader group name: {}, Reader group scope: {}, Online readers: {}",
                readerGroupManager.getReaderGroup(readerGroupName).getGroupName(), readerGroupManager
                        .getReaderGroup(readerGroupName).getScope(), readerGroupManager
                        .getReaderGroup(readerGroupName).getOnlineReaders());
        log.info("Creating {} readers", readers);
        List<EventStreamReader<Long>> readerList = new ArrayList<>(readers);
        List<CompletableFuture<Void>> readerFutureList = new ArrayList<>();
        log.info("Scope that is seen by readers {}", scope);

        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < readers; i++) {
                log.info("Starting reader: {}, with id: {}", i, readerName + i);
                final EventStreamReader<Long> reader = clientFactory.createReader(readerName + i,
                        readerGroupName,
                        new JavaSerializer<Long>(),
                        ReaderConfig.builder().build());
                readerList.add(reader);
                readerFutureList.add(startReading(reader));
            }
        });
        FutureHelpers.completeAfter(() -> FutureHelpers.allOf(readerFutureList), testState.readersComplete);
    }

    void addNewWriters(ClientFactory clientFactory, final int writers, String scope, String stream) {
        log.info("Client factory details {}", clientFactory.toString());
        log.info("Creating {} writers", writers);
        List<EventStreamWriter<Long>> newlyAddedWriterList = new ArrayList<>();
        List<CompletableFuture<Void>> newWritersFutureList = new ArrayList<>();
        log.info("Writers writing in the scope {}", scope);
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < writers; i++) {
                log.info("Starting writer{}", i);
                final EventStreamWriter<Long> tmpWriter = clientFactory.createEventWriter(stream,
                        new JavaSerializer<Long>(),
                        EventWriterConfig.builder().retryAttempts(10).build());
                newlyAddedWriterList.add(tmpWriter);
                newWritersFutureList.add(startWriting(tmpWriter));
            }
        });
        FutureHelpers.completeAfter(() -> FutureHelpers.allOf(newWritersFutureList), testState.newWritersComplete);
    }

    void stopReadersAndWriters(ReaderGroupManager readerGroupManager, String readerGroupName) throws InterruptedException, ExecutionException {

        log.info("Stop write flag status {}", testState.stopWriteFlag);
        testState.stopWriteFlag.set(true);

        log.info("Wait for writers execution to complete");
        testState.writersComplete.get();
        testState.newWritersComplete.get();
        if (testState.getWriteException.get() != null) {
            Assert.fail("Unable to write events. Test failure");
        }

        log.info("Stop read flag status {}", testState.stopReadFlag);
        testState.stopReadFlag.set(true);

        log.info("Wait for readers execution to complete");
        testState.readersComplete.get();
        if (testState.getReadException.get() != null) {
            Assert.fail("Unable to read events. Test failure");
        }

        log.info("All writers have stopped. Setting stopReadFlag. Event Written Count:{}, Event Read " +
                "Count: {}", testState.eventWriteCount.get(), testState.eventsReadFromPravega.size());
        assertEquals(testState.eventWriteCount.get(), testState.eventsReadFromPravega.size());
        assertEquals(testState.eventWriteCount.get(), new TreeSet<>(testState.eventsReadFromPravega).size()); //check unique events.
        log.info("Deleting readergroup {}", readerGroupName);
        readerGroupManager.deleteReaderGroup(readerGroupName);
    }


    static URI startZookeeperInstance() {
        Service zkService = new ZookeeperService("zookeeper");
        if (!zkService.isRunning()) {
            zkService.start(true);
        }
        List<URI> zkUris = zkService.getServiceDetails();
        log.debug("Zookeeper service details: {}", zkUris);
        return zkUris.get(0);
    }

    static void startBookkeeperInstances(final URI zkUri) {
        Service bkService = new BookkeeperService("bookkeeper", zkUri);
        if (!bkService.isRunning()) {
            bkService.start(true);
        }
        List<URI> bkUris = bkService.getServiceDetails();
        log.debug("Bookkeeper service details: {}", bkUris);
    }

    static URI startPravegaControllerInstances(final URI zkUri) {
        Service controllerService = new PravegaControllerService("controller", zkUri);
        if (!controllerService.isRunning()) {
            controllerService.start(true);
        }
        controllerService.scaleService(3, true);
        List<URI> conUris = controllerService.getServiceDetails();
        log.debug("Pravega Controller service  details: {}", conUris);
        // Fetch all the RPC endpoints and construct the client URIs.
        final List<String> uris = conUris.stream().filter(uri -> uri.getPort() == 9092).map(URI::getAuthority)
                .collect(Collectors.toList());
        URI controllerURI = URI.create("tcp://" + String.join(",", uris));
        log.info("Controller Service direct URI: {}", controllerURI);
        return controllerURI;
    }

    static void startPravegaSegmentStoreInstances(final URI zkUri, final URI controllerURI) {
        Service segService = new PravegaSegmentStoreService("segmentstore", zkUri, controllerURI);
        if (!segService.isRunning()) {
            segService.start(true);
        }
        segService.scaleService(3, true);
        List<URI> segUris = segService.getServiceDetails();
        log.debug("Pravega Segmentstore service  details: {}", segUris);
    }

    static class ScaleOperationNotDoneException extends RuntimeException {
    }

}